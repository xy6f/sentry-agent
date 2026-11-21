<script setup>
/**
 * 页面装配层：顶栏徽标 → 拓扑卡（工具条经 slot 注入）→ 三栏（证据/审批/时间线）
 * → 历史表 → API 日志抽屉。跨组件共享状态全部经 Pinia（incident + apiLog）。
 */
import { computed, onMounted } from 'vue';
import { useIncidentStore } from './stores/incident';
import { useApiLogStore } from './stores/apiLog';
import TopBar from './components/TopBar.vue';
import GraphTopology from './components/GraphTopology.vue';
import EvidenceStream from './components/EvidenceStream.vue';
import ApprovalPanel from './components/ApprovalPanel.vue';
import TimelineList from './components/TimelineList.vue';
import HistoryTable from './components/HistoryTable.vue';
import IncidentActions from './components/IncidentActions.vue';
import ApiLogDrawer from './components/ApiLogDrawer.vue';
import StatsPanel from './components/StatsPanel.vue';

const store = useIncidentStore();
const apiLog = useApiLogStore();

const target = computed(() => (store.id ? `事件 #${store.id}` : '—'));

onMounted(() => {
  apiLog.probeApi(); // 旧版启动即探测
  store.loadStats(); // 第四批 4.3：运行统计（落库关闭/失败时面板自动隐藏）
});
</script>

<template>
  <TopBar />
  <main class="layout">
    <GraphTopology
      :timeline="store.timeline"
      :outcome="store.snapshot?.outcome || null"
      :target="target"
      :speed="store.replayMs"
      @set-speed="store.setSpeed"
    >
      <template #actions>
        <IncidentActions />
      </template>
    </GraphTopology>

    <section class="cols">
      <EvidenceStream />
      <ApprovalPanel />
      <TimelineList :entries="store.timeline" :src="store.streamStatus" />
    </section>

    <StatsPanel />
    <HistoryTable />
  </main>
  <ApiLogDrawer />
</template>
