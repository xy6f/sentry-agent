<script setup>
/**
 * MTTD/MTTR 统计面板（第四批 4.3）：数据源 GET /api/incidents/stats（落库底账聚合，
 * 口径与分组规则见 IncidentStatsService 类注释——MTTR 只算 CLOSED、MTTD 只算 watchman
 * 自动建且 CLOSED、自愈率分母为终态数）。
 * 近 7 日 MTTR 用内联 SVG 迷你柱状图（Grafana stat panel 的紧凑形态），
 * 无数据日显灰基准。stats=null（落库关闭/后端未升级）时整卡隐藏不占位。
 */
import { computed } from 'vue';
import { useIncidentStore } from '../stores/incident';

const store = useIncidentStore();

const weekly = computed(() => store.stats?.weekly ?? []);
const maxAvg = computed(() => Math.max(1, ...weekly.value.map(d => Number(d.avgSec) || 0)));

function card(label, value, unit, title) {
  return { label, value, unit, title };
}
const cards = computed(() => {
  const s = store.stats;
  if (!s) return [];
  return [
    card('MTTR 平均', s.mttrAvgSec, 's', 'CLOSED 事件 started_at→closed_at 平均（自愈恢复时长）'),
    card('MTTR P95', s.mttrP95Sec, 's', '恢复时长 95 分位'),
    card('MTTD 自动', s.mttdAutoAvgSec, 's', 'watchman 自动建事件：告警进入→首轮证据到手'),
    card('自愈率', s.selfHealRate, '%', 'CLOSED ÷ 终态数（分母含 ESCALATED，不粉饰）'),
    card('待审批积压', s.awaitingApproval, '', '当前停在 HITL 闸口的事件数'),
    card('自动发现', s.autoCreated, '', 'watchman 创建的事件累计数'),
  ];
});
</script>

<template>
  <section v-if="store.stats" class="panel stats-panel" id="stats-panel">
    <div class="panel-head"><h3>MTTR / MTTD · 运行统计（跨重启底账）</h3></div>
    <div class="stats-grid">
      <div v-for="c in cards" :key="c.label" class="stat-card" :title="c.title">
        <div class="stat-value mono">{{ c.value ?? '—' }}<small v-if="c.unit">{{ c.unit }}</small></div>
        <div class="stat-label">{{ c.label }}</div>
      </div>
    </div>
    <div class="weekly" v-if="weekly.length">
      <div class="weekly-head">近 7 日 MTTR（秒）</div>
      <svg :viewbox="`0 0 ${weekly.length * 44} 56`" class="weekly-chart" preserveAspectRatio="none"
        role="img" aria-label="近七日 MTTR 柱状图">
        <g v-for="(d, i) in weekly" :key="d.date">
          <rect :x="i * 44 + 6" :y="40 - (40 * (Number(d.avgSec) || 0)) / maxAvg"
            :width="32" :height="Math.max(2, (40 * (Number(d.avgSec) || 0)) / maxAvg)"
            :fill="Number(d.count) > 0 ? 'var(--info)' : 'var(--line)'" rx="2"/>
          <text :x="i * 44 + 22" y="52" class="wk-label">{{ d.date.slice(5) }}</text>
          <text :x="i * 44 + 22" y="8" class="wk-value">{{ Number(d.count) > 0 ? d.avgSec : '' }}</text>
        </g>
      </svg>
    </div>
  </section>
</template>

<style scoped>
.stats-grid { display: flex; gap: 10px; flex-wrap: wrap; padding: 10px 12px; }
.stat-card { flex: 1 1 120px; background: var(--bg); border: 1px solid var(--line);
  border-radius: 3px; padding: 8px 10px; }
.stat-value { font-size: 22px; color: var(--txt); }
.stat-value small { font-size: 11px; color: var(--dim); margin-left: 2px; }
.stat-label { font-size: 11px; color: var(--dim); letter-spacing: .5px; text-transform: uppercase;
  margin-top: 2px; }
.weekly { padding: 4px 12px 10px; }
.weekly-head { font-size: 11px; color: var(--dim); letter-spacing: .5px;
  text-transform: uppercase; margin-bottom: 4px; }
.weekly-chart { width: 100%; height: 56px; display: block; }
.wk-label { fill: var(--dim); font-size: 7px; text-anchor: middle; }
.wk-value { fill: var(--txt); font-size: 8px; text-anchor: middle; }
</style>
