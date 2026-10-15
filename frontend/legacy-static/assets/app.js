/* ═══════════════════════════════════════════════════════════
 * sentry-agent · 诊断作战台前端（前后端分离，纯静态：无框架、无构建）
 *
 * 数据来源（字段名以后端代码为准，全部实测对齐）：
 *  - POST /api/dev/seed-logs   {service}            → {file,lines,fetchServiceLogsPreview}
 *  - POST /api/incidents       {service,summary}    → 同步跑图后的 State 快照（无 timeline）
 *  - GET  /api/incidents/{id}  → State 快照 + timeline:[{at,node,digest}]
 *  - GET  /api/incidents/{id}/stream（SSE）→ 命名事件 step_done{at,node,digest} / done{outcome} / error{message}
 *  - POST /api/incidents/{id}/decision {approve}    → 快照（否决时仅 {incidentId,outcome,report}）
 * State 字段（IncidentState）：incidentId/service/alert/evidences[]/hypotheses/verdict/
 *  verifyRound/plan/approvalToken/actions[]/report/outcome/steps/evidenceSeq/hypoSeq/verdictSeq/needsRegen
 * ═══════════════════════════════════════════════════════════ */
'use strict';

/* ── API_BASE：默认 8084，可在 API 日志抽屉里改（存 localStorage） ── */
const apiBase = () => (localStorage.getItem('sentry_api_base') || 'http://localhost:8084').replace(/\/+$/, '');

/* 拓扑节点名与后端 IncidentGraphFactory 注册的节点逐字一致 */
const NODE_LABEL = {
  evidence: '取证', hypothesize: '生成假设', verify: '验证判定', plan: '处置计划',
  awaitApproval: '审批闸口', pending: '挂起等待', execute: '执行处置',
  retrospect: '复盘闭环', escalate: '转人工',
};

const state = {
  id: null, snapshot: null, es: null, pollTimer: null,
  replayToken: 0, replaying: false, replayMs: 550,
  lastNode: 'START', dedup: new Set(), queuedLive: [], busy: false,
};

const $ = (s) => document.querySelector(s);
const h = (tag, cls, text) => {
  const el = document.createElement(tag);
  if (cls) el.className = cls;
  if (text != null) el.textContent = text;
  return el;
};
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const short = (s, n = 140) => (s == null ? '' : String(s).length > n ? String(s).slice(0, n) + '…' : String(s));
const fmtTime = (at) => {
  const d = new Date(at);
  const p = (x, n = 2) => String(x).padStart(n, '0');
  return `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}.${p(d.getMilliseconds(), 3)}`;
};

/* ─────────────── Toast ─────────────── */
function toast(msg, kind = '') {
  const t = h('div', 'toast ' + kind, msg);
  $('#toasts').appendChild(t);
  setTimeout(() => { t.classList.add('out'); setTimeout(() => t.remove(), 400); }, 4200);
}

/* ─────────────── API 日志抽屉 ─────────────── */
const apiLines = [];
function logApi(text, cls) {
  apiLines.push(`${fmtTime(Date.now())} ${cls ? `<i class="${cls}">` : '<b>'}${text}${cls ? '</i>' : '</b>'}`);
  if (apiLines.length > 200) apiLines.shift();
  $('#api-log').innerHTML = apiLines.join('\n');
  $('#drawer-count').textContent = apiLines.length;
  const pre = $('#api-log'); pre.scrollTop = pre.scrollHeight;
}

/* ─────────────── fetch 封装（全部请求走这里，进抽屉日志） ─────────────── */
async function api(method, path, body) {
  const url = apiBase() + path;
  logApi(`${method} ${path}${body ? ' ' + short(JSON.stringify(body), 80) : ''}`);
  const t0 = performance.now();
  let r;
  try {
    r = await fetch(url, {
      method,
      headers: body ? { 'Content-Type': 'application/json' } : {},
      body: body ? JSON.stringify(body) : undefined,
    });
  } catch (e) {
    logApi(`网络失败: ${e.message}`, 'err');
    setApiBadge(false);
    throw new Error(`无法连接 ${apiBase()}（${e.message}）——请确认后端 8084 已启动`);
  }
  const text = await r.text();
  const ms = Math.round(performance.now() - t0);
  logApi(`${r.status} ${ms}ms ${short(text, 110)}`, r.ok ? 'ok' : 'err');
  setApiBadge(r.ok || r.status === 404);
  if (!r.ok) throw new Error(`${method} ${path} → HTTP ${r.status}: ${short(text, 160)}`);
  try { return JSON.parse(text); } catch { return null; }
}

function setApiBadge(ok) {
  const b = $('#badge-api');
  b.textContent = ok ? 'API · 在线' : 'API · 不可达';
  b.className = 'badge ' + (ok ? 'ok' : 'bad');
}
async function probeApi() {
  try { await fetch(apiBase() + '/api/incidents/0', { method: 'GET' }); setApiBadge(true); }
  catch { setApiBadge(false); toast('API 不可达：' + apiBase(), 'err'); }
}

/* ─────────────── 模式徽标推断（后端无状态查询接口，按回包特征判定） ─────────────── */
function inferMode(snap) {
  if (!snap) return;
  const blob = (snap.report || '') + (snap.evidences || []).join(' ');
  const b = $('#badge-mode');
  if (blob.includes('（mock）') || blob.includes('[首轮证据]')) { b.textContent = 'MODE · MOCK'; b.className = 'badge ok'; }
  else if ((snap.evidences || []).length) { b.textContent = 'MODE · 真实 LLM'; b.className = 'badge warn'; }
}

/* ═══════════════ 拓扑绘制 ═══════════════ */
const nodeEl = (n) => $(`#topo .node[data-node="${n}"]`);
const edgeEl = (k) => $(`#topo .edge[data-edge="${k}"]`);

function resetTopo() {
  $('#topo').classList.remove('closing');
  document.querySelectorAll('#topo .node').forEach((g) => { g.classList.remove('lit', 'now', 'wait-approval', 'good', 'bad'); });
  document.querySelectorAll('#topo .edge').forEach((p) => { p.classList.remove('lit', 'draw'); });
  state.lastNode = 'START';
  $('#topo-target').textContent = state.id ? `事件 #${state.id}` : '—';
}

/** 点亮一步：画 from→to 边，前节点转常亮、当前节点脉冲 */
function paintStep(node) {
  let from = state.lastNode;
  /* 幂等重放的第二轮图从 evidence 重入：pending->evidence 无此边，
     视为 START 重新入场，主链描线动画重跑一遍（回环同理） */
  if (from !== 'START' && node === 'evidence' && !edgeEl(`${from}->evidence`)) from = 'START';
  if (from && from !== node) {
    const e = edgeEl(`${from}->${node}`);
    if (e) {
      e.classList.remove('draw');
      void e.getBoundingClientRect(); // 强制重排以便重复触发动画
      e.classList.add('lit');
      if (!e.dataset.dashed) {
        try { e.style.setProperty('--len', String(e.getTotalLength())); } catch { /* 非几何路径兜底 */ }
        e.classList.add('draw');
      }
    }
  }
  const prev = nodeEl(from);
  if (prev) { prev.classList.add('lit'); prev.classList.remove('now'); }
  const cur = nodeEl(node);
  if (cur) { cur.classList.add('lit', 'now'); cur.classList.remove('wait-approval'); }
  state.lastNode = node;
}

/** 终态着色：审批琥珀呼吸 / 闭环绿 + END 脉冲 / 转人工红 */
function paintOutcome(outcome) {
  document.querySelectorAll('#topo .node').forEach((g) => g.classList.remove('now', 'wait-approval', 'good', 'bad'));
  $('#topo').classList.toggle('closing', outcome === 'CLOSED');
  if (outcome === 'AWAITING_APPROVAL') {
    const ap = nodeEl('awaitApproval'), pd = nodeEl('pending');
    if (ap) ap.classList.add('wait-approval');
    if (pd) { pd.classList.add('lit'); }
  } else if (outcome === 'CLOSED') {
    const r = nodeEl('retrospect'); if (r) { r.classList.add('lit', 'good'); }
  } else if (outcome === 'ESCALATED' || outcome === 'FAILED') {
    const e = nodeEl('escalate'); if (e) { e.classList.add('lit', 'bad'); }
  }
}

/* ═══════════════ 时间线（load 重放 / SSE 增量 共用入口） ═══════════════ */
function timelineKey(e) { return `${e.at}|${e.node}`; }

function appendTimelineRow(e) {
  const box = $('#timeline');
  if (box.querySelector('.placeholder')) box.innerHTML = '';
  const item = h('div', 'tl-item nd-' + e.node);
  item.appendChild(h('div', 'tl-time', fmtTime(e.at)));
  item.appendChild(h('span', 'tl-dot'));
  const right = h('div');
  right.appendChild(h('div', 'tl-node', `${e.node} · ${NODE_LABEL[e.node] || ''}`));
  right.appendChild(h('div', 'tl-digest', e.digest || ''));
  item.appendChild(right);
  box.appendChild(item);
  box.scrollTop = box.scrollHeight;
}

/** 一条时间线事件的完整落账：去重 → 行 + 拓扑点亮 */
function ingestStep(e, skipPaint) {
  if (state.dedup.has(timelineKey(e))) return;
  state.dedup.add(timelineKey(e));
  appendTimelineRow(e);
  if (!skipPaint) paintStep(e.node);
}

/** 带节奏的重放：动画中收到的 SSE 增量先入队，播完再冲刷 */
async function replayTimeline(entries) {
  state.replaying = true;
  const tok = ++state.replayToken;
  $('#timeline').innerHTML = '';
  for (const e of entries) {
    if (tok !== state.replayToken) return; // 被新选择打断
    await sleep(state.replayMs);
    ingestStep(e);
  }
  state.replaying = false;
  for (const e of state.queuedLive) ingestStep(e);
  state.queuedLive = [];
}

/* ═══════════════ SSE 流 + 降级轮询 ═══════════════ */
function stopStream() {
  if (state.es) { state.es.close(); state.es = null; }
  if (state.pollTimer) { clearInterval(state.pollTimer); state.pollTimer = null; }
}

function openStream(id) {
  stopStream();
  const url = `${apiBase()}/api/incidents/${id}/stream`;
  logApi(`SSE 订阅 ${url}`);
  $('#tl-src').textContent = 'SSE 实时';
  const es = new EventSource(url);
  state.es = es;

  es.addEventListener('step_done', (ev) => {
    const d = JSON.parse(ev.data);           // {at, node, digest}
    if (state.replaying) state.queuedLive.push(d); // 重放动画期间入队，避免节奏错乱
    else ingestStep(d);
  });
  es.addEventListener('done', (ev) => {
    const d = JSON.parse(ev.data);           // {outcome}
    logApi(`SSE done outcome=${d.outcome}`, 'ok');
    es.close(); state.es = null;
    $('#tl-src').textContent = 'SSE 收流';
    syncDetail(id);                          // 终态后拉全量快照刷新面板
  });
  // 服务端命名事件 error（事件不存在）带 data；纯连接层 error 无 data → 降级
  es.addEventListener('error', (ev) => {
    if (ev && typeof ev.data === 'string') { // 服务端发的命名 error 事件
      es.close(); state.es = null;
      toast('流：' + (JSON.parse(ev.data).message || '未知错误'), 'err');
      return;
    }
    /* 降级说明：EventSource 连接断开（代理切断 / 网络抖动 / 服务端 120s 超时）时，
       自动退化为轮询 GET /api/incidents/{id}（1s 一次），对 timeline 数组按
       (at|node) 键去重增量渲染——时间线条目与 SSE 的 step_done 同源
       （NodeProgressListener），仅实时性从推送降为 1s 轮询粒度。 */
    if (state.es === es) {
      logApi('SSE 断流 → 降级为 1s 轮询详情接口', 'err');
      $('#tl-src').textContent = '轮询降级 1s';
      es.close(); state.es = null;
      if (!state.pollTimer) {
        state.pollTimer = setInterval(async () => {
          try {
            const d = await api('GET', `/api/incidents/${id}`);
            for (const e of (d.timeline || [])) {
              if (state.replaying) state.queuedLive.push(e); else ingestStep(e);
            }
            if (['CLOSED', 'ESCALATED', 'FAILED'].includes(d.outcome)) {
              stopStream();
              $('#tl-src').textContent = '轮询收流';
              syncDetail(id);
            }
          } catch { /* 轮询失败静默重试，API 徽标已反映连通性 */ }
        }, 1000);
      }
    }
  });
}

/* ═══════════════ 左栏：证据 / 假设卡片流 ═══════════════ */
function evidenceCard(s) {
  const c = h('div', 'card');
  const badges = h('div', 'src-badges');
  const first = /^\[首轮证据\]/.test(s);
  badges.appendChild(h('span', 'tag ' + (first ? 'gen' : 'sec'), first ? '首轮取证' : '补充取证'));
  const src = s.includes('logs[') ? 'LOG' : s.includes('metrics[') ? 'METRICS'
    : s.includes('containers') ? 'CONTAINER' : s.includes('deploys[') ? 'DEPLOY' : 'EVIDENCE';
  badges.appendChild(h('span', 'tag src', src));
  if (s.includes('验证项')) badges.appendChild(h('span', 'tag src', 'BY-VERIFY-METHOD'));
  c.appendChild(badges);
  c.appendChild(h('p', 'body', s));
  return c;
}

/** hypotheses 行契约："N. 假设 | 验证方法"；verdict 行契约："N | 成立|不成立 | 依据" */
function renderLeft(snap) {
  const evBox = $('#evidence-stream'); evBox.innerHTML = '';
  const evs = snap.evidences || [];
  $('#evid-count').textContent = evs.length ? `×${evs.length}` : '';
  if (!evs.length) evBox.appendChild(h('p', 'placeholder', '该事件暂无证据（尚未走到 evidence 节点）。'));
  evs.forEach((s) => evBox.appendChild(evidenceCard(s)));

  const hyBox = $('#hypo-stream'); hyBox.innerHTML = '';
  const hLines = (snap.hypotheses || '').split('\n').map((l) => l.trim()).filter(Boolean);
  const vLines = (snap.verdict || '').split('\n').map((l) => l.trim()).filter(Boolean);
  const verdictByIdx = {};
  for (const l of vLines) {
    const m = l.match(/^(\d+)[.、]?\s*\|\s*(不成立|成立)\s*\|\s*(.*)$/);
    if (m) verdictByIdx[m[1]] = { held: m[2] === '成立', why: m[3] };
  }
  if (!hLines.length) { hyBox.appendChild(h('p', 'placeholder', '假设与判定将在 hypothesize / verify 节点产出后出现。')); return; }
  hLines.forEach((line, i) => {
    const m = line.match(/^(\d+)[.、]?\s*(.*?)\s*\|\s*(.+)$/);
    const idx = m ? m[1] : String(i + 1);
    const claim = m ? m[2] : line;
    const method = m ? m[3] : '';
    const v = verdictByIdx[idx];
    const c = h('div', 'card' + (v ? (v.held ? ' hypo-held' : ' hypo-dead') : ''));
    const badges = h('div', 'src-badges');
    badges.appendChild(h('span', 'tag gen', '假设 ' + idx));
    if (v) badges.appendChild(h('span', 'tag ' + (v.held ? 'hold' : 'noh'), v.held ? '判定成立 ✓' : '判定不成立 ✕'));
    c.appendChild(badges);
    c.appendChild(h('p', 'body', claim + (method ? `\n验证方法：${method}` : '')));
    if (v) c.appendChild(h('p', 'body', '判定依据：' + v.why));
    hyBox.appendChild(c);
  });
  const extra = vLines.filter((l) => !/^\d+[.、]?\s*\|/.test(l));
  if (extra.length) hyBox.appendChild(h('p', 'body', extra.join('\n')));
}

/* ═══════════════ 中栏：审批面板（plan 解析镜像后端 PlanParser 契约） ═══════════════ */
const PLAN_ACTIONS = new Set(['RESTART', 'SCALE', 'ROLLBACK', 'NONE']);
const PLAN_RISKS = new Set(['LOW', 'HIGH']);
function parsePlan(planText) {
  const out = [];
  if (!planText || !planText.trim()) return out;
  for (const raw of planText.split(/\r?\n/)) {
    const line = raw.trim();
    if (!line) continue;
    const p = line.split('|');
    if (p.length !== 4) { logApi(`PlanParser 镜像：丢弃非法行(${p.length}段) ${short(line, 60)}`); continue; }
    const [type, target, risk, reason] = [p[0].trim().toUpperCase(), p[1].trim(), p[2].trim().toUpperCase(), p[3].trim()];
    if (!PLAN_ACTIONS.has(type) || !PLAN_RISKS.has(risk) || !target) { logApi(`PlanParser 镜像：丢弃非法行 ${short(line, 60)}`); continue; }
    out.push({ type, target, risk, reason });
  }
  return out;
}

function renderApproval(snap) {
  const body = $('#approval-body'); body.innerHTML = '';
  const armed = snap.outcome === 'AWAITING_APPROVAL';
  $('#col-mid').classList.toggle('armed', armed);
  $('#btn-approve').disabled = !armed || state.busy;
  $('#btn-veto').disabled = !armed || state.busy;

  const st = $('#approval-state');
  st.textContent = snap.outcome || 'RUNNING';
  st.style.color = armed ? 'var(--amber)' : snap.outcome === 'CLOSED' ? 'var(--green)'
    : ['ESCALATED', 'FAILED'].includes(snap.outcome) ? 'var(--red)' : '';

  if (armed) {
    const note = h('div', 'card');
    note.appendChild(h('p', 'body', '⛨ 处置计划含高危动作且尚未取得一次性审批令牌——批准将签发令牌并续跑图（execute→retrospect），否决直接终结转人工。'));
    body.appendChild(note);
  } else if (snap.outcome === 'CLOSED') {
    const note = h('div', 'card hypo-held');
    note.appendChild(h('p', 'body', '✔ 已闭环。处置动作执行完毕，复盘报告见下方。'));
    body.appendChild(note);
  } else if (['ESCALATED', 'FAILED'].includes(snap.outcome)) {
    const note = h('div', 'card hypo-dead');
    note.appendChild(h('p', 'body', '✕ 已转人工：' + short(snap.report, 200)));
    body.appendChild(note);
  } else {
    body.appendChild(h('p', 'placeholder', `当前状态 ${snap.outcome || 'RUNNING'}；到审批闸口后此面板亮起。`));
  }

  const acts = parsePlan(snap.plan);
  if (!acts.length && snap.plan) {
    body.appendChild(h('div', 'card', null)).appendChild(h('p', 'body', 'NONE —— 无需自动处置（approvalRouter 直跳 retrospect）。'));
  }
  acts.forEach((a) => {
    const c = h('div', 'card');
    const b = h('div', 'src-badges');
    b.appendChild(h('span', 'tag type', a.type));
    b.appendChild(h('span', 'tag ' + (a.risk === 'HIGH' ? 'high' : 'low'), '风险 ' + a.risk));
    c.appendChild(b);
    c.appendChild(h('p', 'body', `目标：${a.target}\n理由：${a.reason}`));
    body.appendChild(c);
  });
  (snap.actions || []).forEach((r) => {
    const c = h('div', 'card hypo-held');
    c.appendChild(h('p', 'body', r));
    body.appendChild(c);
  });
  $('#report-box').textContent = snap.report || '—';
}

/* ═══════════════ 快照渲染 + 事件装载 ═══════════════ */
function renderOutcomeChip(outcome) {
  const b = $('#badge-outcome');
  b.textContent = outcome ? `事件 #${state.id} · ${outcome}` : '未选中事件';
  b.className = 'badge outcome-chip ' + (outcome === 'CLOSED' ? 'ok'
    : outcome === 'AWAITING_APPROVAL' ? 'warn' : ['ESCALATED', 'FAILED'].includes(outcome) ? 'bad' : '');
}

function renderPanels(snap) {
  state.snapshot = snap;
  inferMode(snap);
  renderLeft(snap);
  renderApproval(snap);
  renderOutcomeChip(snap.outcome);
  paintOutcome(snap.outcome);
  upsertHistory(snap);
}

/** GET 详情 → 面板刷新（SSE done / 轮询终态 / 决策返回后统一走这里） */
async function syncDetail(id) {
  try {
    const d = await api('GET', '/api/incidents/' + id);
    if (state.id !== id) return;
    renderPanels(d);
    // 补齐动画期间或降级窗口漏进的时间线尾部
    for (const e of d.timeline || []) {
      if (state.replaying) state.queuedLive.push(e); else ingestStep(e);
    }
  } catch (e) { toast(short(e.message), 'err'); }
}

async function loadIncident(id, createSnap) {
  stopStream();
  state.dedup = new Set(); state.queuedLive = [];
  state.id = id; state.busy = false;
  resetTopo();
  $('#timeline').innerHTML = '';
  try {
    // 创建接口是同步跑图，返回即有完整快照；timeline 需从详情接口取（同源登记处）
    const snap = await api('GET', '/api/incidents/' + id);
    renderPanels(snap);
    await replayTimeline(snap.timeline || []);
    if (!['CLOSED', 'ESCALATED', 'FAILED'].includes(snap.outcome)) openStream(id);
    else $('#tl-src').textContent = '历史重放';
    void createSnap;
  } catch (e) { toast(e.message, 'err'); }
}

/* ═══════════════ 动作：注入剧本 / 手动创建 / 审批决策 ═══════════════ */
async function seedScenario() {
  if (state.busy) return;
  state.busy = true; $('#btn-seed').disabled = true;
  try {
    /* DevToolsController.SeedReq 仅有 service 字段（无 scenario 参数），
       剧本内容是后端内置的"队列堆积"脚本，这里以代码为准只传 service */
    const seed = await api('POST', '/api/dev/seed-logs', { service: 'judge-service' });
    toast(`剧本日志已注入：${seed.file}（${seed.lines} 行）`, 'ok');
    const snap = await api('POST', '/api/incidents', {
      service: 'judge-service',
      summary: '队列堆积告警：queueDepth 320 > 阈值 200 且持续爬升，多容器 timeout killed',
    });
    toast(`事件 #${snap.incidentId} 创建完成，outcome=${snap.outcome}`, 'ok');
    await loadIncident(snap.incidentId, snap);
  } catch (e) { toast(e.message, 'err'); }
  finally { state.busy = false; $('#btn-seed').disabled = false; }
}

async function manualCreate() {
  const service = $('#m-service').value.trim() || 'judge-service';
  const summary = $('#m-summary').value.trim() || '人工上报异常';
  $('#manual-modal').hidden = true;
  if (state.busy) return;
  state.busy = true;
  try {
    const snap = await api('POST', '/api/incidents', { service, summary });
    toast(`事件 #${snap.incidentId} 创建完成，outcome=${snap.outcome}`, 'ok');
    await loadIncident(snap.incidentId, snap);
  } catch (e) { toast(e.message, 'err'); }
  finally { state.busy = false; }
}

async function decide(approve) {
  if (!state.id || !state.snapshot || state.busy) return;
  if (state.snapshot.outcome !== 'AWAITING_APPROVAL') return;
  state.busy = true;
  renderApproval(state.snapshot); // 按钮置灰，防连点
  /* 决策前确保流在：首次 stream 可能已因 120s 超时断开——重开一次，
     done 事件回来再统一 syncDetail，拓扑就跟着实时推进 */
  if (!state.es && !state.pollTimer) openStream(state.id);
  try {
    const ret = await api('POST', `/api/incidents/${state.id}/decision`, { approve });
    toast(approve ? '✔ 已批准，一次性令牌签发，图续跑中' : `✕ 已否决：${short(ret.report, 80)}`, approve ? 'ok' : 'warn');
    await syncDetail(state.id);
  } catch (e) { toast(e.message, 'err'); }
  finally { state.busy = false; if (state.snapshot) renderApproval(state.snapshot); }
}

/* ═══════════════ 事件历史（localStorage 持久，点击重放拓扑动画） ═══════════════ */
const HIST_KEY = 'sentr…t_v1';
const getHist = () => { try { return JSON.parse(localStorage.getItem(HIST_KEY)) || []; } catch { return []; } };
const saveHist = (l) => localStorage.setItem(HIST_KEY, JSON.stringify(l.slice(0, 50)));

function upsertHistory(snap) {
  const list = getHist();
  const rec = {
    id: snap.incidentId, service: snap.service || '?', summary: short(snap.alert, 60),
    outcome: snap.outcome || 'RUNNING', steps: snap.steps ?? '—', seen: Date.now(),
  };
  const i = list.findIndex((x) => x.id === rec.id);
  if (i >= 0) list[i] = rec; else list.unshift(rec);
  saveHist(list);
  renderHistory();
}

function renderHistory() {
  const body = $('#hist-body');
  const list = getHist();
  body.innerHTML = '';
  if (!list.length) { body.appendChild(h('tr', null)).appendChild(h('td', 'placeholder', '— 暂无 —')).colSpan = 7; return; }
  list.forEach((r) => {
    const tr = h('tr', r.id === state.id ? 'cur' : '');
    tr.appendChild(h('td', 'mono', '#' + r.id));
    tr.appendChild(h('td', null, r.service));
    tr.appendChild(h('td', null, r.summary));
    tr.appendChild(h('td', null)).appendChild(h('span', 'st ' + r.outcome, r.outcome));
    tr.appendChild(h('td', 'mono', String(r.steps)));
    tr.appendChild(h('td', 'mono dim', fmtTime(r.seen)));
    const td = h('td');
    const play = h('button', 'btn tiny ghost', '▶ 重放');
    play.onclick = (ev) => { ev.stopPropagation(); loadIncident(r.id); };
    td.appendChild(play); tr.appendChild(td);
    tr.onclick = () => loadIncident(r.id);
    body.appendChild(tr);
  });
}

/* ═══════════════ 绑定 ═══════════════ */
$('#btn-seed').onclick = seedScenario;
$('#btn-manual').onclick = () => { $('#manual-modal').hidden = false; $('#m-service').focus(); };
$('#m-cancel').onclick = () => { $('#manual-modal').hidden = true; };
$('#m-create').onclick = manualCreate;
$('#manual-modal').onclick = (e) => { if (e.target === e.currentTarget) e.currentTarget.hidden = true; };
$('#btn-approve').onclick = () => decide(true);
$('#btn-veto').onclick = () => decide(false);
$('#btn-clear-hist').onclick = () => { localStorage.removeItem(HIST_KEY); renderHistory(); };
$('#drawer-tab').onclick = () => { $('#drawer').hidden = !$('#drawer').hidden; };
$('#drawer-close').onclick = () => { $('#drawer').hidden = true; };
$('#api-base').value = apiBase();
$('#api-base').onchange = (e) => {
  localStorage.setItem('sentry_api_base', e.target.value.trim().replace(/\/+$/, ''));
  toast('API_BASE → ' + apiBase(), 'ok'); probeApi();
};
$('#speed-ctrl').querySelectorAll('button').forEach((b) => {
  b.onclick = () => {
    $('#speed-ctrl .on')?.classList.remove('on');
    b.classList.add('on');
    state.replayMs = Number(b.dataset.ms);
  };
});

/* 启动 */
renderHistory();
probeApi();
