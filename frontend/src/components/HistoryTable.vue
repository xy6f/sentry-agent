<script setup>
/**
 * 底部：事件历史表（localStorage 持久，键与旧版一致，行结构同款），
 * 点击行或「▶ 重放」载入该事件并按节奏重放拓扑动画（store.loadIncident）。
 */
import { useIncidentStore } from '../stores/incident';
import { fmtTime } from '../lib/parsers';

const store = useIncidentStore();

const rowClass = ({ row }) => (row.id === store.id ? 'cur-row' : '');
const play = (row) => store.loadIncident(row.id);
</script>

<template>
  <section class="glass history-card">
    <div class="head">
      <h2 class="panel-title">事件历史</h2>
      <button class="btn tiny ghost" @click="store.clearHistory()">清空</button>
    </div>
    <el-table
      :data="store.history" size="small" :row-class-name="rowClass"
      @row-click="play" style="width: 100%"
    >
      <el-table-column label="ID" width="70">
        <template #default="{ row }"><span class="mono">#{{ row.id }}</span></template>
      </el-table-column>
      <el-table-column prop="service" label="服务" width="150" />
      <el-table-column prop="summary" label="告警摘要" min-width="220" show-overflow-tooltip />
      <el-table-column label="状态" width="180">
        <template #default="{ row }"><span class="st" :class="row.outcome">{{ row.outcome }}</span></template>
      </el-table-column>
      <el-table-column label="步数" width="70">
        <template #default="{ row }"><span class="mono">{{ row.steps }}</span></template>
      </el-table-column>
      <el-table-column label="记录时间" width="120">
        <template #default="{ row }"><span class="mono dim">{{ fmtTime(row.seen) }}</span></template>
      </el-table-column>
      <el-table-column width="90">
        <template #default="{ row }">
          <button class="btn tiny ghost" @click.stop="play(row)">▶ 重放</button>
        </template>
      </el-table-column>
      <template #empty><span class="placeholder">— 暂无 —</span></template>
    </el-table>
  </section>
</template>

<style scoped>
.history-card { padding: 12px 16px 12px; }
.head { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 8px; }
.mono { font-family: var(--mono); }
.btn {
  cursor: pointer; border-radius: 2px; padding: 6px 12px; font-size: 12.5px;
  color: var(--txt); border: 1px solid var(--line); background: var(--panel-3);
  transition: background .15s ease, border-color .15s ease;
}
.btn.ghost:hover { border-color: var(--dim); }
.btn.tiny { padding: 4px 10px; font-size: 12px; }
:deep(.el-table__row) { cursor: pointer; }
</style>
