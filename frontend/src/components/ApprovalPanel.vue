<script setup>
/**
 * 中栏：审批面板。
 *  - plan 文本按「动作 | 目标 | 风险 | 理由」四段解析为动作卡列表（lib/parsers.js
 *    parsePlan 镜像后端 PlanParser：白名单 + 目标非空，非法行丢弃并写入 API 日志抽屉，
 *    与旧版 logApi('PlanParser 镜像：丢弃非法行…') 同款行为）；
 *  - outcome=AWAITING_APPROVAL 时面板琥珀武装、按钮可点；批准/否决 → store.decide →
 *    新快照回流 → 拓扑继续推进（execute→retrospect 或终结转人工）；
 *  - 执行结果 actions[] 与复盘报告 report 原样展示。
 */
import { computed } from 'vue';
import { useIncidentStore } from '../stores/incident';
import { useApiLogStore } from '../stores/apiLog';
import { parsePlan, short } from '../lib/parsers';

const store = useIncidentStore();
const apiLog = useApiLogStore();

const snap = computed(() => store.snapshot || {});
const armed = computed(() => snap.value.outcome === 'AWAITING_APPROVAL');
const closed = computed(() => snap.value.outcome === 'CLOSED');
const dead = computed(() => ['ESCALATED', 'FAILED'].includes(snap.value.outcome));

const stateColor = computed(() =>
  armed.value ? 'var(--warn)' : closed.value ? 'var(--ok)' : dead.value ? 'var(--crit)' : '');

// 非法行丢弃 → API 日志抽屉（镜像后端 log.warn 的可见性）
const actions = computed(() =>
  parsePlan(snap.value.plan, (msg) => apiLog.log(`PlanParser 镜像：${msg}`)));
const nonePlan = computed(() => !actions.value.length && Boolean(snap.value.plan));
</script>

<template>
  <div class="glass col col-approval" :class="{ armed }" id="col-mid">
    <h2 class="col-title">审批面板
      <span class="mono dim state" :style="{ color: stateColor }">{{ snap.outcome || 'IDLE' }}</span>
    </h2>

    <div class="approval-body">
      <div v-if="armed" class="card">
        <p class="body">⛨ 处置计划含高危动作且尚未取得一次性审批令牌——批准将签发令牌并续跑图（execute→retrospect），否决直接终结转人工。</p>
      </div>
      <div v-else-if="closed" class="card hypo-held">
        <p class="body">✔ 已闭环。处置动作执行完毕，复盘报告见下方。</p>
      </div>
      <div v-else-if="dead" class="card hypo-dead">
        <p class="body">✕ 已转人工：{{ short(snap.report, 200) }}</p>
      </div>
      <p v-else class="placeholder">当前状态 {{ snap.outcome || 'RUNNING' }}；到审批闸口后此面板亮起。</p>

      <div v-if="nonePlan" class="card">
        <p class="body">NONE —— 无需自动处置（approvalRouter 直跳 retrospect）。</p>
      </div>
      <div v-for="(a, i) in actions" :key="i" class="card">
        <div class="src-badges">
          <span class="tag type">{{ a.type }}</span>
          <span class="tag" :class="a.risk === 'HIGH' ? 'high' : 'low'">风险 {{ a.risk }}</span>
        </div>
        <p class="body">目标：{{ a.target }}
理由：{{ a.reason }}</p>
      </div>
      <div v-for="(r, i) in snap.actions || []" :key="'r'+i" class="card hypo-held">
        <p class="body">{{ r }}</p>
      </div>
    </div>

    <div class="approval-actions">
      <button class="btn big approve" :disabled="!armed || store.busy" @click="store.decide(true)">✔ 批准执行</button>
      <button class="btn big veto" :disabled="!armed || store.busy" @click="store.decide(false)">✕ 否决转人工</button>
    </div>

    <h2 class="col-title mt">复盘报告</h2>
    <pre class="mono report">{{ snap.report || '—' }}</pre>
  </div>
</template>

<style scoped>
.col-approval { transition: border-color .3s; }
.col-approval.armed { border-color: rgba(242,204,12,.55); }
.state { color: var(--dim); text-transform: uppercase; }
.col-approval.armed .state { animation: stateBreath 2s ease-in-out infinite; }
.approval-body { flex: 1; overflow-y: auto; min-height: 100px; }
.approval-actions { display: flex; gap: 8px; margin-top: 12px; }
.btn {
  cursor: pointer; border-radius: 2px; font-size: 13px;
  color: var(--txt); border: 1px solid var(--line); background: var(--panel-3);
  transition: background .15s ease, border-color .15s ease;
}
.btn:disabled { opacity: .35; cursor: not-allowed; }
.btn.big { flex: 1; padding: 10px; font-weight: 600; letter-spacing: .5px; }
.btn.approve { background: var(--info); color: #fff; border: none; }
.btn.approve:not(:disabled):hover { background: #3f83e8; }
.btn.veto { border-color: rgba(242,73,92,.5); color: var(--crit); background: transparent; }
.btn.veto:hover:not(:disabled) { background: rgba(242,73,92,.12); }
</style>
