/**
 * 拓扑常量：节点名与后端 IncidentGraphFactory.addNode 注册的 9 个节点逐字一致，
 * 边集合与图定义（含条件边 Map 的全部可达转移）一致，共 14 条边。
 * 时间线 timeline[].node 取值即这里的 data-node 名。
 */
export const NODE_LABEL = {
  evidence: '取证', hypothesize: '生成假设', verify: '验证判定', plan: '处置计划',
  awaitApproval: '审批闸口', pending: '挂起等待', execute: '执行处置',
  retrospect: '复盘闭环', escalate: '转人工',
};

/**
 * 14 条边（key = `from->to`，与旧版 app.js 的 data-edge 一致）。
 * dashed：verify→evidence 回环、awaitApproval→pending 停等出图、pending→END（出图虚线）；
 * bad：通向 escalate 的红箭头分支。
 */
export const EDGES = [
  { key: 'START->evidence', d: 'M26,150 L48,150' },
  { key: 'evidence->hypothesize', d: 'M138,150 L162,150' },
  { key: 'hypothesize->verify', d: 'M298,150 L322,150' },
  { key: 'verify->plan', d: 'M458,150 L482,150' },
  // verify 回环：虚线弧回到 evidence
  { key: 'verify->evidence', d: 'M389,124 C330,52 145,52 76,124', dashed: true },
  // verify -> escalate 分支
  { key: 'verify->escalate', d: 'M389,176 C389,215 389,235 389,254', bad: true },
  { key: 'plan->awaitApproval', d: 'M618,150 L652,150' },
  // awaitApproval -> execute（批准）
  { key: 'awaitApproval->execute', d: 'M788,138 C812,112 830,100 848,92' },
  // awaitApproval -> pending（停等出图）
  { key: 'awaitApproval->pending', d: 'M720,176 C720,205 720,225 720,242', dashed: true },
  // awaitApproval -> retrospect（NONE 计划跳过执行）
  { key: 'awaitApproval->retrospect', d: 'M770,174 C800,212 820,226 842,232' },
  { key: 'execute->retrospect', d: 'M910,114 C910,155 910,190 910,206' },
  { key: 'retrospect->END', d: 'M972,234 L1084,234' },
  { key: 'escalate->END', d: 'M452,282 C640,320 940,300 1084,244', bad: true },
  { key: 'pending->END', d: 'M782,262 C900,290 1030,270 1084,246', dashed: true },
];

export const EDGE_BY_KEY = Object.fromEntries(EDGES.map((e) => [e.key, e]));

export const hasEdge = (key) => Boolean(EDGE_BY_KEY[key]);
