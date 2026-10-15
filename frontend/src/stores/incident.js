import { defineStore } from 'pinia';
import { ref, computed } from 'vue';
import { ElMessage } from 'element-plus';
import { useApiLogStore } from './apiLog';
import { openIncidentStream } from '../api/sse';
import { inferMode, short, fmtTime } from '../lib/parsers';

const HIST_KEY = 'sentr…y_v1';
const getHist = () => { try { return JSON.parse(localStorage.getItem(HIST_KEY)) || []; } catch { return []; } };
const saveHist = (l) => localStorage.setItem(HIST_KEY, JSON.stringify(l.slice(0, 50)));

/**
 * 事件主 store：当前事件快照 + 时间线（含播放进度与重放节奏）+ 拓扑点亮状态 + 历史列表。
 * 全部逻辑继承自旧版 assets/app.js（通读对照见 README 功能对照清单）：
 *  - 时间线去重键 `at|node`；重放期间 SSE 增量入队、播完冲刷；
 *  - 幂等重放第二轮回环：pending->evidence 等无此边时视为 START 重新入场；
 *  - AWAITING_APPROVAL 终态开流跟进度；决策前确保流在（120s 超时断开则重开）。
 */
export const useIncidentStore = defineStore('incident', () => {
  const apiLog = useApiLogStore();

  /* ── 当前事件 ── */
  const id = ref(null);
  const snapshot = ref(null);              // GET /api/incidents/{id} 详情快照（含 timeline）
  const timeline = ref([]);                // 已落账/已重放出的时间线条目（GraphTopology 的 props 源）
  const outcome = computed(() => snapshot.value?.outcome || (id.value ? 'RUNNING' : null));

  /* 拓扑点亮状态不在此处：store 只按节奏向 timeline 追加条目（含去重），
     GraphTopology.vue 以 props 接收 timeline，内部逐条目点亮（描边/脉冲）。 */

  /* ── 重放节奏与流 ── */
  const replaying = ref(false);
  const replayMs = ref(550);               // 慢 900 / 1× 550 / 4× 250
  const streamStatus = ref('—');           // 时间线来源标注：SSE 实时 / 轮询降级 1s / 历史重放 …
  const busy = ref(false);
  const dedup = new Set();
  const queuedLive = [];
  let replayToken = 0;
  let abortCtl = null;                     // 当前 SSE fetch 的 AbortController
  let pollTimer = null;

  const modeBadge = computed(() => inferMode(snapshot.value));
  const outcomeChip = computed(() => {
    if (!id.value) return { text: '未选中事件', cls: '' };
    const o = outcome.value;
    return {
      text: `事件 #${id.value} · ${o}`,
      cls: o === 'CLOSED' ? 'ok' : o === 'AWAITING_APPROVAL' ? 'warn'
        : ['ESCALATED', 'FAILED'].includes(o) ? 'bad' : '',
    };
  });

  /* ═══════════ fetch 封装（旧版 api()：全部请求走这里，进抽屉日志） ═══════════ */
  async function request(method, path, body) {
    const base = apiLog.apiBase;
    const url = base + path;
    apiLog.log(`${method} ${path}${body ? ' ' + short(JSON.stringify(body), 80) : ''}`);
    const t0 = performance.now();
    let r;
    try {
      r = await fetch(url, {
        method,
        headers: body ? { 'Content-Type': 'application/json' } : {},
        body: body ? JSON.stringify(body) : undefined,
      });
    } catch (e) {
      apiLog.log(`网络失败: ${e.message}`, 'err');
      apiLog.setApiBadge(false);
      throw new Error(`无法连接 ${base || '同源代理 /api'}（${e.message}）——请确认后端 8084 已启动`);
    }
    const text = await r.text();
    const ms = Math.round(performance.now() - t0);
    apiLog.log(`${r.status} ${ms}ms ${short(text, 110)}`, r.ok ? 'ok' : 'err');
    apiLog.setApiBadge(r.ok || r.status === 404);
    if (!r.ok) throw new Error(`${method} ${path} → HTTP ${r.status}: ${short(text, 160)}`);
    try { return JSON.parse(text); } catch { return null; }
  }

  /* ═══════════ 时间线：去重落账 + 带节奏重放（旧版 ingestStep/replayTimeline） ═══════════
   * 拓扑点亮动画不在 store 里做：store 只按节奏向 timeline 追加条目，
   * GraphTopology.vue 通过 props 观察新条目并逐歩点亮（描边/脉冲/琥珀呼吸）。 */
  const keyOf = (e) => `${e.at}|${e.node}`;

  function ingest(e) {
    if (dedup.has(keyOf(e))) return;
    dedup.add(keyOf(e));
    timeline.value.push(e);
  }

  async function replay(entries) {
    replaying.value = true;
    const tok = ++replayToken;
    timeline.value = [];
    for (const e of entries) {
      if (tok !== replayToken) return;     // 被新选择打断
      await new Promise((r) => setTimeout(r, replayMs.value));
      ingest(e);
    }
    replaying.value = false;
    for (const e of queuedLive.splice(0)) ingest(e); // 播完冲刷动画期间入队的实时增量
  }

  function ingestLive(e) {
    if (replaying.value) queuedLive.push(e); // 重放动画期间入队，避免节奏错乱
    else ingest(e);
  }

  /* ═══════════ SSE（fetch+ReadableStream）+ 降级轮询 ═══════════ */
  function stopStream() {
    if (abortCtl) { abortCtl.abort(); abortCtl = null; }
    if (pollTimer) { clearInterval(pollTimer); pollTimer = null; }
  }

  function openStream() {
    stopStream();
    const cur = id.value;
    if (cur == null) return;
    apiLog.log(`SSE 订阅(fetch 流) /api/incidents/${cur}/stream`);
    streamStatus.value = 'SSE 实时';
    abortCtl = new AbortController();
    openIncidentStream({
      id: cur,
      base: apiLog.apiBase,
      signal: abortCtl.signal,
      onStep: (d) => ingestLive(d),                 // {at,node,digest}，与 step_done 同源增量 append
      onDone: (d) => {                              // {outcome} 终态
        apiLog.log(`SSE done outcome=${d.outcome}`, 'ok');
        abortCtl = null;
        streamStatus.value = 'SSE 收流';
        syncDetail(cur);                            // 终态后拉全量快照刷新面板
      },
      onServerError: (d) => {                       // 服务端命名 error 事件（如事件不存在）
        abortCtl = null;
        ElMessage.error('流：' + (d.message || '未知错误'));
      },
      /* 降级说明：连接层失败 / 服务端 120s 超时主动收流 / 代理切断——
         自动退化为轮询 GET /api/incidents/{id}（1s 一次），对 timeline 数组按
         (at|node) 键去重增量渲染，条目与 SSE step_done 同源（NodeProgressListener），
         仅实时性从推送降为 1s 轮询粒度。旧版 EventSource 同款策略原样保留。 */
      onDisconnect: (e) => {
        if (id.value !== cur) return;               // 已切走，不降级
        apiLog.log(`SSE 断流(${short(e?.message || '', 60)}) → 降级为 1s 轮询详情接口`, 'err');
        abortCtl = null;
        startPolling(cur);
      },
    });
  }

  function startPolling(cur) {
    if (pollTimer) return;
    streamStatus.value = '轮询降级 1s';
    pollTimer = setInterval(async () => {
      try {
        const d = await request('GET', `/api/incidents/${cur}`);
        for (const e of d.timeline || []) ingestLive(e);
        if (['CLOSED', 'ESCALATED', 'FAILED'].includes(d.outcome)) {
          stopStream();
          streamStatus.value = '轮询收流';
          syncDetail(cur);
        }
      } catch { /* 轮询失败静默重试，API 徽标已反映连通性 */ }
    }, 1000);
  }

  /* ═══════════ 快照刷新与事件装载（旧版 syncDetail/loadIncident） ═══════════ */
  async function syncDetail(cur) {
    try {
      const d = await request('GET', '/api/incidents/' + cur);
      if (id.value !== cur) return;
      snapshot.value = d;
      upsertHistory(d);
      // 补齐动画期间或降级窗口漏进的时间线尾部
      for (const e of d.timeline || []) ingestLive(e);
    } catch (e) { ElMessage.error(short(e.message)); }
  }

  async function loadIncident(curId) {
    stopStream();
    dedup.clear(); queuedLive.length = 0;
    id.value = curId;
    busy.value = false;
    timeline.value = []; // 置空触发 GraphTopology 内部复位
    try {
      // 创建接口是同步跑图返回即有完整快照；timeline 需从详情接口取（同源登记处）
      const snap = await request('GET', '/api/incidents/' + curId);
      snapshot.value = snap;
      upsertHistory(snap);
      await replay(snap.timeline || []);
      // 修复（API 日志实测）：判定终态要用"当前"快照而非进函数时的 snap——重放期间
      // 用户可能已下决策（syncDetail 回流 ESCALATED/CLOSED），拿旧 snap.outcome 判定
      // 会给已终结的事件白白重开一条 SSE 流（实测否决路径多订阅一次、done 即断）。
      if (!['CLOSED', 'ESCALATED', 'FAILED'].includes(snapshot.value?.outcome ?? snap.outcome)) openStream();
      else streamStatus.value = '历史重放';
    } catch (e) { ElMessage.error(short(e.message)); }
  }

  /* ═══════════ 动作：一键注入剧本 / 手动创建 / 审批决策 ═══════════ */
  async function seedScenario() {
    if (busy.value) return;
    busy.value = true;
    try {
      /* DevToolsController.SeedReq 仅有 service 字段（无 scenario 参数），
         剧本内容是后端内置的"队列堆积"脚本，以代码为准只传 service */
      const seed = await request('POST', '/api/dev/seed-logs', { service: 'judge-service' });
      ElMessage.success(`剧本日志已注入：${seed.file}（${seed.lines} 行）`);
      const snap = await request('POST', '/api/incidents', {
        service: 'judge-service',
        summary: '队列堆积告警：queueDepth 320 > 阈值 200 且持续爬升，多容器 timeout killed',
      });
      ElMessage.success(`事件 #${snap.incidentId} 创建完成，outcome=${snap.outcome}`);
      await loadIncident(snap.incidentId);
    } catch (e) { ElMessage.error(short(e.message)); }
    finally { busy.value = false; }
  }

  async function manualCreate({ service, summary }) {
    if (busy.value) return;
    busy.value = true;
    try {
      const snap = await request('POST', '/api/incidents', {
        service: service.trim() || 'judge-service',
        summary: summary.trim() || '人工上报异常',
      });
      ElMessage.success(`事件 #${snap.incidentId} 创建完成，outcome=${snap.outcome}`);
      await loadIncident(snap.incidentId);
    } catch (e) { ElMessage.error(short(e.message)); }
    finally { busy.value = false; }
  }

  async function decide(approve) {
    if (!id.value || !snapshot.value || busy.value) return;
    if (snapshot.value.outcome !== 'AWAITING_APPROVAL') return;
    busy.value = true;
    /* 决策前确保流在：首次 stream 可能已因 120s 超时断开——重开一次，
       done 事件回来再统一 syncDetail，拓扑就跟着实时推进（旧版同款策略） */
    if (!abortCtl && !pollTimer) openStream();
    try {
      const ret = await request('POST', `/api/incidents/${id.value}/decision`, { approve });
      ElMessage({
        type: approve ? 'success' : 'warning',
        message: approve ? '✔ 已批准，一次性令牌签发，图续跑中' : `✕ 已否决：${short(ret.report, 80)}`,
      });
      await syncDetail(id.value);
    } catch (e) { ElMessage.error(short(e.message)); }
    finally { busy.value = false; }
  }

  /* ═══════════ 事件历史（localStorage 持久，点击载入重放；旧版同键同构） ═══════════ */
  const history = ref(getHist());

  function upsertHistory(snap) {
    const list = getHist();
    const rec = {
      id: snap.incidentId, service: snap.service || '?', summary: short(snap.alert, 60),
      outcome: snap.outcome || 'RUNNING', steps: snap.steps ?? '—', seen: Date.now(),
    };
    const i = list.findIndex((x) => x.id === rec.id);
    if (i >= 0) list[i] = rec; else list.unshift(rec);
    saveHist(list);
    history.value = list;
  }

  function clearHistory() {
    localStorage.removeItem(HIST_KEY);
    history.value = [];
  }

  function setSpeed(ms) { replayMs.value = ms; }

  return {
    id, snapshot, outcome, timeline, streamStatus, busy, replaying, replayMs,
    modeBadge, outcomeChip, history,
    request, loadIncident, syncDetail, openStream, stopStream,
    seedScenario, manualCreate, decide, clearHistory, setSpeed,
    fmtTime,
  };
});
