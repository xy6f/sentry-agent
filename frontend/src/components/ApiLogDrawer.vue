<script setup>
/**
 * API 日志抽屉（el-drawer 替代旧版手写抽屉）：全部请求流水、API_BASE 改写入口。
 * 默认空串 = 同源走 vite proxy（5176 → 8084）；填绝对地址即直连后端（CorsConfig 放行）。
 */
import { ref, watch, nextTick } from 'vue';
import { useApiLogStore } from '../stores/apiLog';
import { useIncidentStore } from '../stores/incident';

const apiLog = useApiLogStore();
const incident = useIncidentStore();
const baseInput = ref(apiLog.apiBase);
const pre = ref(null);

watch(() => apiLog.lines.length, async () => {
  await nextTick();
  if (pre.value) pre.value.scrollTop = pre.value.scrollHeight;
});

function saveBase() {
  apiLog.setApiBase(baseInput.value);
  incident.request('GET', '/api/incidents').catch(() => {}); // 立即探测 + 留痕（列表端点恒 200，不再制造 404 console error）
}
</script>

<template>
  <button class="drawer-tab" @click="apiLog.drawerOpen = true">
    API 日志 <span class="mono">{{ apiLog.count }}</span>
  </button>
  <el-drawer v-model="apiLog.drawerOpen" title="API 调用日志" size="min(560px, 95vw)" :with-header="true">
    <div class="drawer-head">
      <label class="api-base">API_BASE
        <input v-model="baseInput" spellcheck="false" @change="saveBase"
          placeholder="留空 = 同源走 vite proxy /api → 8084" />
      </label>
      <button class="btn tiny ghost" @click="apiLog.drawerOpen = false">收起</button>
    </div>
    <pre id="api-log" ref="pre"><span
      v-for="(l, i) in apiLog.lines" :key="i"
      :class="l.cls || 'req'"
>{{ l.t }} {{ l.text }}
</span></pre>
  </el-drawer>
</template>

<style scoped>
.drawer-tab {
  position: fixed; left: 18px; bottom: 18px; z-index: 50; cursor: pointer;
  font: 12px var(--mono); color: var(--dim); background: rgba(10,20,38,.85);
  border: 1px solid var(--line); border-radius: 10px; padding: 8px 13px;
}
.drawer-tab:hover { color: var(--cyan); border-color: rgba(34,211,238,.5); }
.mono { font-family: var(--mono); }
.drawer-head { display: flex; justify-content: space-between; align-items: center; gap: 10px; margin-bottom: 10px; }
.api-base { font: 11px var(--mono); color: var(--dim); display: flex; align-items: center; gap: 8px; }
.api-base input {
  width: 250px; background: rgba(5,11,24,.8); color: var(--txt);
  border: 1px solid var(--line); border-radius: 7px; padding: 4px 8px; font: 11px var(--mono);
}
.btn {
  cursor: pointer; border-radius: 10px; color: var(--txt);
  border: 1px solid var(--line); background: rgba(12,24,44,.8);
}
.btn.tiny { padding: 4px 10px; font-size: 12px; }
.btn.ghost:hover { border-color: rgba(34,211,238,.5); }
</style>
