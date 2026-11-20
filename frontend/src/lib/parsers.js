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

/* ── 证据卡（旧版 evidenceCard 的解析规则 + 不足清单第 4 项的定向徽章） ──
 * "[首轮证据]" 前缀 → 首轮取证；"[补充证据-定向]" 前缀 → verify 节点按假设
 * verifyAction 的定向补充取证；其余为兼容路径的普通补充取证；
 * 来源徽章按文本特征：logs[ → LOG，metrics[ → METRICS，containers → CONTAINER，
 * deploys[ → DEPLOY，其余 EVIDENCE；含「验证项」追加 BY-VERIFY-METHOD。 */
export function parseEvidence(s) {
  const first = /^\[首轮证据\]/.test(s);
  const targeted = /^\[补充证据-定向\]/.test(s);
  const src = s.includes('logs[') ? 'LOG'
    : s.includes('metrics[') ? 'METRICS'
      : s.includes('containers') ? 'CONTAINER'
        : s.includes('deploys[') ? 'DEPLOY' : 'EVIDENCE';
  return { text: s, first, targeted, src, byMethod: s.includes('验证项') };
}

/* ── 假设 / 判定卡（不足清单第 4 项：结构化 JSON 优先、旧文本兜底） ──
 * hypotheses 行契约（新）：每行一个 JSON {"cause":...,"confidence":0-100,"verifyAction":"..."}；
 * hypotheses 行契约（旧兜底）："N. 假设 | 验证方法"；
 * verdict 行契约（新）：每行一个 JSON {"index":N,"conclusion":"成立|不成立|存疑","reason":"..."}；
 * verdict 行契约（旧兜底）："N | 成立|不成立 | 依据"。
 * JSON 行解析失败自动回退旧文本正则（与后端 HypothesisParser 同一解析次序，
 * 渐进迁移不破坏旧剧本）；判定按 index 挂到对应假设卡上。 */
function tryJson(line) {
  if (!line.startsWith('{')) return null;
  try { return JSON.parse(line); } catch { return null; }
}

export function parseHypotheses(snap) {
  const hLines = (snap.hypotheses || '').split('\n').map((l) => l.trim()).filter(Boolean);
  const vLines = (snap.verdict || '').split('\n').map((l) => l.trim()).filter(Boolean);
  const verdictByIdx = {};
  for (const l of vLines) {
    const j = tryJson(l);
    if (j && j.index != null) {
      const conclusion = j.conclusion || '';
      verdictByIdx[String(j.index)] = { held: conclusion === '成立', conclusion, why: j.reason || '' };
      continue;
    }
    const m = l.match(/^(\d+)[.、]?\s*\|\s*(不成立|成立|存疑)\s*\|\s*(.*)$/);
    if (m) verdictByIdx[m[1]] = { held: m[2] === '成立', conclusion: m[2], why: m[3] };
  }
  const cards = [];
  hLines.forEach((line, i) => {
    const j = tryJson(line);
    if (j && j.cause) {
      cards.push({
        idx: String(i + 1),
        claim: String(j.cause),
        method: j.verifyAction || '',
        confidence: Number.isFinite(Number(j.confidence)) && j.confidence !== '' ? Number(j.confidence) : null,
        verdict: verdictByIdx[String(i + 1)] || null,
      });
      return;
    }
    const m = line.match(/^(\d+)[.、]?\s*(.*?)\s*\|\s*(.+)$/);
    if (m) {
      cards.push({ idx: m[1], claim: m[2], method: m[3], confidence: null, verdict: verdictByIdx[m[1]] || null });
    } else {
      // 旧版宽容行为：无竖线行整行当假设（验证方法缺失，序号按行序）
      cards.push({ idx: String(i + 1), claim: line, method: '', confidence: null, verdict: verdictByIdx[String(i + 1)] || null });
    }
  });
  // 既不是合法 JSON 判定、也不匹配旧文本判定的杂行 → extra（原样展示不静默吞掉）
  const extra = vLines.filter((l) => {
    const j = tryJson(l);
    if (j && j.index != null) return false;
    return !/^\d+[.、]?\s*\|/.test(l);
  });
  return { cards, extra };
}
