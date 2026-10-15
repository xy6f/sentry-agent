<script setup>
/**
 * 诊断状态图拓扑（手绘 SVG，9 节点 / 14 边，节点名与后端 IncidentGraphFactory 逐字一致，
 * 含 verify→evidence 回环虚线弧）。
 *
 * 动画机制（继承旧版 app.js 的 paintStep，改为响应式实现）：
 *  - props.timeline 每追加一条 {at,node,digest}，watch 增量点亮一步：
 *    描边走线动画 = CSS stroke-dashoffset（path 长度在 mounted 时 getTotalLength 实测写入 --len），
 *    同一条边被第二次经过（回环/幂等重放入场）时 drawSeq 递增 → :key 变化重挂 path → 动画重跑
 *    （等价旧版 remove class + void getBoundingClientRect 强制重排的技巧）；
 *  - 当前节点脉冲光环（.now 呼吸 ring），前节点转常亮（.lit）；
 *  - AWAITING_APPROVAL：approval 节点琥珀呼吸（.wait-approval）、pending 常亮；
 *    CLOSED：retrospect 绿 + svg.closing 让 END 脉冲；ESCALATED/FAILED：escalate 红呼吸；
 *  - 重放倍速（慢 900ms / 1× 550ms / 4× 250ms）由父级 store 的 replayMs 控制条目到达节奏，
 *    本组件只负责"条目到达即播一步"，天然跟随倍速，无需 rAF 计时器。
 *
 * 幂等重放第二轮回环规则原样保留（旧版注释同款）：上一站与 evidence 之间没有直达边
 * （如 pending->evidence）时，视为 START 重新入场，主链描线动画重跑一遍。
 */
import { ref, reactive, computed, watch, onMounted } from 'vue';
import { EDGES, EDGE_BY_KEY, NODE_LABEL, hasEdge } from '../lib/topology';

const props = defineProps({
  timeline: { type: Array, default: () => [] },  // [{at,node,digest}]，按时间序追加
  outcome: { type: String, default: null },       // 当前快照 outcome（终态着色）
  target: { type: String, default: '—' },         // 顶栏事件标识
  speed: { type: Number, default: 550 },
});
const emit = defineEmits(['set-speed']);

const litNodes = ref(new Set());
const litEdges = ref(new Set());
const current = ref(null);
const lastNode = ref('START');
const drawEdge = ref({ key: null, seq: 0 });      // seq 递增重触发同一条边的描线动画
const lens = reactive({});                        // key → path 长度（描边动画用）
let pulled = 0;                                   // 已拉入点亮队列的 timeline 条目数
const queue = [];                                 // 待点亮的节点名队列
let paintTimer = null;                            // 队列积压时的节奏定时器

const SPEEDS = [
  { label: '慢', ms: 900 },
  { label: '1×', ms: 550 },
  { label: '4×', ms: 250 },
];

function resetPaint() {
  litNodes.value = new Set();
  litEdges.value = new Set();
  current.value = null;
  lastNode.value = 'START';
  drawEdge.value = { key: null, seq: drawEdge.value.seq };
  queue.length = 0;
  if (paintTimer) { clearTimeout(paintTimer); paintTimer = null; }
  pulled = 0;
}

/** 点亮一步（旧版 paintStep 的等价移植） */
function paintStep(node) {
  let from = lastNode.value;
  if (from !== 'START' && node === 'evidence' && !hasEdge(`${from}->evidence`)) from = 'START';
  if (from && from !== node && hasEdge(`${from}->${node}`)) {
    const key = `${from}->${node}`;
    litEdges.value = new Set(litEdges.value).add(key);
    drawEdge.value = { key, seq: drawEdge.value.seq + 1 };
  }
  if (from && from !== 'START') litNodes.value = new Set(litNodes.value).add(from);
  current.value = node;
  lastNode.value = node;
}

// 监听 length：store 用 push 增量追加（引用不变，watch 数组本身不会触发），
// 重放重置时数组被整体替换为空——length 回落即复位。
// 修复（批准路径实测）：决策后后端同步跑完续段，SSE/syncDetail 会把 7 条 step 同一拍
// 批量灌入，原实现在 watch 回调里同步连播 7 步 paintStep——描边动画只看得见最后一步，
// 拓扑"跳"到终态。改为点亮队列：单条到达即播（不增加延迟），批量积压按重放节奏
// （props.speed）逐条补播，execute→retrospect 恢复逐节点推进观感。
function drainQueue() {
  paintTimer = null;
  if (!queue.length) return;
  paintStep(queue.shift());
  if (queue.length) paintTimer = setTimeout(drainQueue, props.speed);
}

watch(() => props.timeline.length, (len) => {
  if (len < pulled) resetPaint();                // 数组被重置（切换/重放新事件）
  for (; pulled < len; pulled++) queue.push(props.timeline[pulled].node);
  if (!paintTimer && queue.length) drainQueue();
}, { immediate: true });

const closing = computed(() => props.outcome === 'CLOSED');

function nodeCls(name) {
  const o = props.outcome;
  return {
    lit: litNodes.value.has(name) || (o === 'AWAITING_APPROVAL' && name === 'pending'),
    now: current.value === name,
    'wait-approval': o === 'AWAITING_APPROVAL' && name === 'awaitApproval',
    good: o === 'CLOSED' && name === 'retrospect',
    bad: ['ESCALATED', 'FAILED'].includes(o) && name === 'escalate',
  };
}

function edgeCls(e) {
  return {
    lit: litEdges.value.has(e.key),
    draw: drawEdge.value.key === e.key && !e.dashed,   // 实线：stroke-dashoffset 描线动画
  };
}

/** :key 绑定：当前被描画的边 seq 变化 → 重挂 → 动画从头重跑 */
function edgeKey(e) {
  return drawEdge.value.key === e.key ? `${e.key}:${drawEdge.value.seq}` : e.key;
}

onMounted(() => {
  // 静态 d 属性一次实测各边长度，写入 --len 供描边动画使用
  for (const e of EDGES) {
    if (e.dashed) continue;
    const el = document.querySelector(`#topo path[data-edge="${CSS.escape(e.key)}"]`);
    if (el) { try { lens[e.key] = Math.ceil(el.getTotalLength()); } catch { /* 兜底 */ } }
  }
});
</script>

<template>
  <section class="glass topo-card">
    <div class="topo-head">
      <h2>诊断状态图 <span class="mono live-target">{{ target }}</span></h2>
      <div class="toolbar">
        <div class="speed" title="拓扑重放动画速度">
          <button
            v-for="s in SPEEDS" :key="s.ms"
            :class="{ on: speed === s.ms }"
            @click="emit('set-speed', s.ms)"
          >{{ s.label }}</button>
        </div>
        <slot name="actions" />
      </div>
    </div>

    <div class="topo-wrap">
      <svg id="topo" viewBox="0 0 1160 330" preserveAspectRatio="xMidYMid meet" :class="{ closing }">
        <defs>
          <linearGradient id="grad-edge" x1="0" y1="0" x2="1" y2="0">
            <stop offset="0" stop-color="#22d3ee"/><stop offset="1" stop-color="#3b82f6"/>
          </linearGradient>
          <linearGradient id="grad-node" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0" stop-color="#0c1a33"/><stop offset="1" stop-color="#071022"/>
          </linearGradient>
          <radialGradient id="grad-glow" cx="0.5" cy="0.5" r="0.5">
            <stop offset="0" stop-color="#22d3ee" stop-opacity="0.5"/>
            <stop offset="1" stop-color="#22d3ee" stop-opacity="0"/>
          </radialGradient>
          <marker id="arw" viewBox="0 0 8 8" refX="7" refY="4" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
            <path d="M0,0 L8,4 L0,8 Z" fill="#22d3ee"/>
          </marker>
          <marker id="arw-red" viewBox="0 0 8 8" refX="7" refY="4" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
            <path d="M0,0 L8,4 L0,8 Z" fill="#f87171"/>
          </marker>
        </defs>

        <!-- ── 边（先画，节点盖其上）：data-edge 与后端图定义的转移逐一对应 ── -->
        <g id="edges" fill="none">
          <path
            v-for="e in EDGES" :key="edgeKey(e)"
            class="edge" :class="[edgeCls(e), { dashed: e.dashed, 'edge-bad': e.bad }]"
            :data-edge="e.key" :data-dashed="e.dashed ? '1' : undefined"
            :d="e.d" :style="{ '--len': (lens[e.key] || 0) + 'px' }"
          />
          <g class="edge-labels" font-size="10.5">
            <text x="258" y="66" class="elbl">不成立 · 回环重取证</text>
            <!-- 修复：verify/plan 矩形间净宽仅 28px，"成立"两字横排塞不进（先后被
                 plan、verify 节点矩形盖住）。抬到矩形顶边之上（y=120 < 126）水平居中。 -->
            <text x="459" y="120" class="elbl">成立</text>
            <text x="404" y="222" class="elbl bad">耗尽/超限</text>
            <text x="796" y="112" class="elbl">批准</text>
            <text x="730" y="216" class="elbl">停等出图</text>
            <text x="786" y="212" class="elbl">NONE 直跳</text>
          </g>
        </g>

        <!-- ── 端点 ── -->
        <g class="terminal"><circle cx="20" cy="150" r="7" class="t-start"/><text x="20" y="128" class="t-lbl">START</text></g>
        <g class="terminal"><circle cx="1092" cy="234" r="9" class="t-end"/><text x="1092" y="210" class="t-lbl">END</text></g>

        <!-- ── 节点：data-node 与后端 timeline[].node 逐字一致 ── -->
        <g class="node" data-node="evidence" :class="nodeCls('evidence')" transform="translate(72,150)">
          <circle class="pulse" r="46"/><rect class="nrect" x="-66" y="-24" rx="10" width="132" height="48"/>
          <text class="nname" y="-3">evidence</text><text class="nzh" y="13">{{ NODE_LABEL.evidence }}</text>
        </g>
        <g class="node" data-node="hypothesize" :class="nodeCls('hypothesize')" transform="translate(230,150)">
          <circle class="pulse" r="46"/><rect class="nrect" x="-66" y="-24" rx="10" width="132" height="48"/>
          <text class="nname" y="-3">hypothesize</text><text class="nzh" y="13">{{ NODE_LABEL.hypothesize }}</text>
        </g>
        <g class="node" data-node="verify" :class="nodeCls('verify')" transform="translate(390,150)">
          <circle class="pulse" r="46"/><rect class="nrect" x="-66" y="-24" rx="10" width="132" height="48"/>
          <text class="nname" y="-3">verify</text><text class="nzh" y="13">{{ NODE_LABEL.verify }}</text>
        </g>
        <g class="node" data-node="plan" :class="nodeCls('plan')" transform="translate(550,150)">
          <circle class="pulse" r="46"/><rect class="nrect" x="-66" y="-24" rx="10" width="132" height="48"/>
          <text class="nname" y="-3">plan</text><text class="nzh" y="13">{{ NODE_LABEL.plan }}</text>
        </g>
        <g class="node node-approval" data-node="awaitApproval" :class="nodeCls('awaitApproval')" transform="translate(720,150)">
          <circle class="pulse" r="50"/><rect class="nrect" x="-68" y="-24" rx="10" width="136" height="48"/>
          <text class="nname" y="-3">awaitApproval</text><text class="nzh" y="13">⛨ 人工审批闸口</text>
        </g>
        <g class="node" data-node="execute" :class="nodeCls('execute')" transform="translate(910,90)">
          <circle class="pulse" r="46"/><rect class="nrect" x="-62" y="-24" rx="10" width="124" height="48"/>
          <text class="nname" y="-3">execute</text><text class="nzh" y="13">{{ NODE_LABEL.execute }}</text>
        </g>
        <g class="node node-good" data-node="retrospect" :class="nodeCls('retrospect')" transform="translate(910,232)">
          <circle class="pulse" r="46"/><rect class="nrect" x="-62" y="-24" rx="10" width="124" height="48"/>
          <text class="nname" y="-3">retrospect</text><text class="nzh" y="13">{{ NODE_LABEL.retrospect }}</text>
        </g>
        <g class="node node-pending" data-node="pending" :class="nodeCls('pending')" transform="translate(720,264)">
          <circle class="pulse" r="44"/><rect class="nrect" x="-60" y="-20" rx="10" width="120" height="40"/>
          <text class="nname" y="-2">pending</text><text class="nzh" y="12">{{ NODE_LABEL.pending }}</text>
        </g>
        <g class="node node-bad" data-node="escalate" :class="nodeCls('escalate')" transform="translate(390,280)">
          <circle class="pulse" r="44"/><rect class="nrect" x="-62" y="-20" rx="10" width="124" height="40"/>
          <text class="nname" y="-2">escalate</text><text class="nzh" y="12">{{ NODE_LABEL.escalate }}</text>
        </g>
      </svg>
    </div>
  </section>
</template>

<style scoped>
.topo-card { padding: 16px 20px 8px; }
.topo-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; margin-bottom: 8px; }
.topo-head h2 { font-size: 15px; letter-spacing: .5px; }
.live-target { color: var(--cyan); font-size: 13px; }
.toolbar { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; }
.mono { font-family: var(--mono); }

.topo-wrap { width: 100%; }
#topo { width: 100%; height: auto; display: block; }

.edge { stroke: rgba(110,150,200,.30); stroke-width: 1.6; marker-end: url(#arw); opacity: .9; }
.edge.dashed { stroke-dasharray: 5 6; }
.edge.edge-bad { marker-end: url(#arw-red); }
.edge.lit { stroke: url(#grad-edge); stroke-width: 2.6; filter: drop-shadow(0 0 4px rgba(34,211,238,.7)); }
.edge.lit.edge-bad { stroke: var(--red); filter: drop-shadow(0 0 4px rgba(248,113,113,.6)); }
/* 描线动画：mounted 实测 path 长度写入 --len，dashoffset 从满到 0 */
.edge.draw { stroke-dasharray: var(--len); stroke-dashoffset: var(--len); animation: drawEdge .5s ease forwards; }
@keyframes drawEdge { to { stroke-dashoffset: 0; } }
.edge.dashed.lit { stroke-dasharray: 5 6; animation: flashEdge 1s ease; }
@keyframes flashEdge { 30% { opacity: .2; } 60% { opacity: 1; } }
.elbl { fill: var(--dim); font-size: 10.5px; font-family: var(--mono); }
.elbl.bad { fill: rgba(248,113,113,.75); }

.terminal circle { fill: #0a1526; stroke: var(--dim); stroke-width: 1.4; }
.terminal .t-start { fill: rgba(34,211,238,.25); }
.t-lbl { fill: var(--dim); font: 10px var(--mono); text-anchor: middle; }
#topo.closing .t-end { fill: var(--green); stroke: var(--green); animation: endPop 1.6s infinite; }
@keyframes endPop { 50% { opacity: .4; transform: scale(1.25); } }
.terminal .t-end { transform-box: fill-box; transform-origin: center; }

.node { transition: opacity .3s; }
.node .nrect { fill: url(#grad-node); stroke: rgba(110,150,200,.35); stroke-width: 1.4; transition: stroke .3s; }
.node .nname { fill: var(--txt); font: 600 13px var(--mono); text-anchor: middle; }
.node .nzh { fill: var(--dim); font-size: 10px; text-anchor: middle; }
.node .pulse { fill: url(#grad-glow); opacity: 0; transform-box: fill-box; transform-origin: center; }
.node.lit .nrect { stroke: var(--cyan); filter: drop-shadow(0 0 6px rgba(34,211,238,.35)); }
.node.lit .nname { fill: #eafcff; }
/* 当前节点：脉冲光环呼吸 */
.node.now .pulse { opacity: 1; animation: ring 1.15s ease-out infinite; }
@keyframes ring { 0% { transform: scale(.55); opacity: .9; } 100% { transform: scale(1.35); opacity: 0; } }
.node.now .nrect { stroke: var(--cyan); stroke-width: 2.2; }
/* 等待审批：琥珀呼吸 */
.node.wait-approval .nrect { stroke: var(--amber); fill: rgba(251,191,36,.08); animation: amberBreath 1.8s ease-in-out infinite; }
.node.wait-approval .nname { fill: var(--amber); }
@keyframes amberBreath { 50% { opacity: .45; } }
/* 终态着色 */
.node.good .nrect { stroke: var(--green); fill: rgba(52,211,153,.10); }
.node.good .nname { fill: var(--green); }
.node.bad .nrect { stroke: var(--red); fill: rgba(248,113,113,.10); animation: amberBreath 1.6s infinite; }
.node.bad .nname { fill: var(--red); }

.speed { display: flex; border: 1px solid var(--line); border-radius: 9px; overflow: hidden; }
.speed button { cursor: pointer; border: none; background: transparent; color: var(--dim); font: 12px var(--mono); padding: 7px 11px; }
.speed button.on { background: rgba(34,211,238,.16); color: var(--cyan); }
</style>
