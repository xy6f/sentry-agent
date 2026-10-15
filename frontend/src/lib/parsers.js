/**
 * 纯函数解析层：全部镜像后端代码契约（字段与正则与旧版 app.js 逐条一致，
 * 旧版又是按后端源码对齐的），组件/store 只做展示编排。
 */

/* ── 通用格式化 ── */
export const short = (s, n = 140) =>
  s == null ? '' : String(s).length > n ? String(s).slice(0, n) + '…' : String(s);

export const fmtTime = (at) => {
  const d = new Date(at);
  const p = (x, n = 2) => String(x).padStart(n, '0');
  return `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}.${p(d.getMilliseconds(), 3)}`;
};

/* ── 模式徽标推断（后端无状态查询接口，按回包特征判定；旧版 inferMode 原样迁入） ── */
export function inferMode(snap) {
  if (!snap) return null;
  const blob = (snap.report || '') + (snap.evidences || []).join(' ');
  if (blob.includes('（mock）') || blob.includes('[首轮证据]')) return 'MOCK';
  if ((snap.evidences || []).length) return 'LLM';
  return null;
}

/* ── PlanParser 镜像（后端 com.sentryagent.graph.PlanParser） ──
 * 契约：每行「动作 | 目标 | 风险 | 理由」竖线四段；
 * 动作白名单 RESTART/SCALE/ROLLBACK/NONE，风险白名单 LOW/HIGH，目标非空；
 * 非法行（段数≠4 / 白名单外 / 目标空）一律丢弃，并回报给日志抽屉（旧版同款行为）。 */
export const PLAN_ACTIONS = new Set(['RESTART', 'SCALE', 'ROLLBACK', 'NONE']);
export const PLAN_RISKS = new Set(['LOW', 'HIGH']);

export function parsePlan(planText, onDrop = () => {}) {
  const out = [];
  if (!planText || !planText.trim()) return out;
  for (const raw of planText.split(/\r?\n/)) {
    const line = raw.trim();
    if (!line) continue;
    const p = line.split('|');
    if (p.length !== 4) { onDrop(`丢弃非法行(${p.length}段) ${short(line, 60)}`); continue; }
    const type = p[0].trim().toUpperCase();
    const target = p[1].trim();
    const risk = p[2].trim().toUpperCase();
    const reason = p[3].trim();
    if (!PLAN_ACTIONS.has(type) || !PLAN_RISKS.has(risk) || !target) {
      onDrop(`丢弃非法行 ${short(line, 60)}`);
      continue;
    }
    out.push({ type, target, risk, reason });
  }
  return out;
}

/* ── 证据卡（旧版 evidenceCard 的解析规则） ──
 * "[首轮证据]" 前缀 → 首轮取证，否则补充取证；
 * 来源徽章按文本特征：logs[ → LOG，metrics[ → METRICS，containers → CONTAINER，
 * deploys[ → DEPLOY，其余 EVIDENCE；含「验证项」追加 BY-VERIFY-METHOD。 */
export function parseEvidence(s) {
  const first = /^\[首轮证据\]/.test(s);
  const src = s.includes('logs[') ? 'LOG'
    : s.includes('metrics[') ? 'METRICS'
      : s.includes('containers') ? 'CONTAINER'
        : s.includes('deploys[') ? 'DEPLOY' : 'EVIDENCE';
  return { text: s, first, src, byMethod: s.includes('验证项') };
}

/* ── 假设 / 判定卡（旧版 renderLeft 的解析规则） ──
 * hypotheses 行契约："N. 假设 | 验证方法"；
 * verdict 行契约："N | 成立|不成立 | 依据"（序号对齐挂到对应假设卡上）。 */
export function parseHypotheses(snap) {
  const hLines = (snap.hypotheses || '').split('\n').map((l) => l.trim()).filter(Boolean);
  const vLines = (snap.verdict || '').split('\n').map((l) => l.trim()).filter(Boolean);
  const verdictByIdx = {};
  for (const l of vLines) {
    const m = l.match(/^(\d+)[.、]?\s*\|\s*(不成立|成立)\s*\|\s*(.*)$/);
    if (m) verdictByIdx[m[1]] = { held: m[2] === '成立', why: m[3] };
  }
  const cards = hLines.map((line, i) => {
    const m = line.match(/^(\d+)[.、]?\s*(.*?)\s*\|\s*(.+)$/);
    const idx = m ? m[1] : String(i + 1);
    const claim = m ? m[2] : line;
    const method = m ? m[3] : '';
    return { idx, claim, method, verdict: verdictByIdx[idx] || null };
  });
  const extra = vLines.filter((l) => !/^\d+[.、]?\s*\|/.test(l));
  return { cards, extra };
}
