<script setup>
/**
 * 顶栏：品牌 + 四枚徽章（模式推断 / Watchman / API 连通 / 事件终态），
 * 逻辑与旧版 index.html + app.js 的徽标区一一对应（inferMode 迁入 parsers.js，store 暴露 computed）。
 */
import { computed } from 'vue';
import { useIncidentStore } from '../stores/incident';
import { useApiLogStore } from '../stores/apiLog';

const store = useIncidentStore();
const apiLog = useApiLogStore();

const mode = computed(() => {
  const m = store.modeBadge;
  return m === 'MOCK'
    ? { text: 'MODE · MOCK', cls: 'ok' }
    : m === 'LLM'
      ? { text: 'MODE · 真实 LLM', cls: 'warn' }
      : { text: 'MODE · MOCK', cls: '' };
});
const api = computed(() => apiLog.apiOk === null
  ? { text: 'API · 探测中', cls: '' }
  : apiLog.apiOk
    ? { text: 'API · 在线', cls: 'ok' }
    : { text: 'API · 不可达', cls: 'bad' });
</script>

<template>
  <header class="topbar">
    <div class="brand">
      <span class="brand-dot"></span>
      <h1>sentry-agent <em>· 诊断作战大屏</em></h1>
      <span class="brand-sub">Vue 3 + Vite + Element Plus + Pinia · LangGraph4j AIOps 诊断自愈 Agent</span>
    </div>
    <div class="badges">
      <span class="badge" :class="mode.cls" id="badge-mode"
        title="llm.enabled 默认 false（application.yml），走 MockDiagnoserGateway 剧本回包；后端无状态查询接口，徽标在拿到首个事件数据后按回包特征自动改判">
        {{ mode.text }}
      </span>
      <span class="badge" id="badge-watchman"
        title="sentry.watchman.enabled 默认 false（application.yml）；后端无状态接口，此为配置默认值展示">
        WATCHMAN · OFF
      </span>
      <span class="badge" :class="api.cls" id="badge-api" title="连通性探测：任意一次 API 成功即绿">
        {{ api.text }}
      </span>
      <span class="badge outcome-chip" :class="store.outcomeChip.cls" id="badge-outcome">
        {{ store.outcomeChip.text }}
      </span>
    </div>
  </header>
</template>

<style scoped>
.topbar {
  display: flex; align-items: center; justify-content: space-between; gap: 16px;
  padding: 14px 22px; flex-wrap: wrap;
  border-bottom: 1px solid var(--line);
  background: linear-gradient(180deg, rgba(8,15,30,.9), rgba(5,11,24,.55));
}
.brand { display: flex; align-items: baseline; gap: 12px; flex-wrap: wrap; }
.brand h1 {
  font-size: 21px; font-weight: 700; letter-spacing: .5px;
  background: linear-gradient(90deg, var(--cyan), var(--blue));
  -webkit-background-clip: text; background-clip: text; color: transparent;
}
.brand h1 em { font-style: normal; }
.brand-sub { color: var(--dim); font-size: 12px; }
.brand-dot {
  width: 10px; height: 10px; border-radius: 50%; align-self: center;
  background: linear-gradient(135deg, var(--cyan), var(--blue));
  box-shadow: 0 0 12px var(--cyan);
  animation: brandBreath 2.6s ease-in-out infinite;
}
@keyframes brandBreath { 50% { transform: scale(1.25); opacity: .75; } }
.badges { display: flex; gap: 8px; flex-wrap: wrap; }
</style>
