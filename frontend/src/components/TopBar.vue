<script setup>
/**
 * 顶栏（Grafana 风格）：左侧 logo + 低调产品名 sentry-agent + 环境选择器形态徽章，
 * 右侧为时间范围 / 刷新间隔两个假控件（形态对齐 Grafana 顶栏，仅展示不含逻辑）
 * + 四枚状态徽章（模式推断 / Watchman / API 连通 / 事件终态）。
 * 逻辑与旧版 index.html + app.js 的徽标区一一对应（inferMode 迁入 parsers.js，store 暴露 computed）。
 */
import { computed, onMounted, onUnmounted, ref } from 'vue';
import { useIncidentStore } from '../stores/incident';
import { useApiLogStore } from '../stores/apiLog';

const store = useIncidentStore();
const apiLog = useApiLogStore();

/* 第四批 4.2：待审批积压徽章（简化版通知中心——数据来自 /api/incidents?status=
   AWAITING_APPROVAL，10s 轮询；>0 时琥珀计数、点击跳历史表过滤视图暂以
   store.loadIncident 选中首个待审批事件代替）。 */
const pending = ref(0);
let pendingTimer = null;
async function tickPending() {
  await store.refreshPending();
  pending.value = store.pendingCount;
}
onMounted(() => { tickPending(); pendingTimer = setInterval(tickPending, 10000); });
onUnmounted(() => clearInterval(pendingTimer));
async function jumpPending() {
  if (!pending.value) return;
  const list = await store.request('GET', '/api/incidents?status=AWAITING_APPROVAL&page=0&size=1')
    .catch(() => []);
  const first = (Array.isArray(list) ? list : list?.items ?? [])[0];
  if (first?.id) store.loadIncident(Number(first.id));
}

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
      <svg class="logo" viewBox="0 0 16 16" aria-hidden="true">
        <circle cx="8" cy="8" r="6.5" fill="none" stroke="var(--info)" stroke-width="1.5"/>
        <circle cx="8" cy="8" r="2.5" fill="var(--info)"/>
      </svg>
      <h1>sentry-agent</h1>
      <span class="env-badge" title="当前演示环境（假环境选择器徽章，形态对齐 Grafana org/env picker）">
        <span class="env-dot"></span>DEV
      </span>
    </div>
    <div class="controls">
      <button class="fake-ctl" title="时间范围选择器（展示形态，非功能控件）">
        <svg viewBox="0 0 12 12" class="ctl-icon" aria-hidden="true">
          <path d="M1.5 6.5l3 3 6-7" fill="none" stroke="currentColor" stroke-width="1.4"/>
        </svg>
        Last 6 hours
        <svg viewBox="0 0 12 12" class="ctl-icon caret" aria-hidden="true">
          <path d="M2.5 4.5L6 8l3.5-3.5" fill="none" stroke="currentColor" stroke-width="1.4"/>
        </svg>
      </button>
      <button class="fake-ctl icon-only" title="刷新（展示形态，非功能控件）">
        <svg viewBox="0 0 12 12" class="ctl-icon" aria-hidden="true">
          <path d="M10 6a4 4 0 1 1-1.2-2.8M10 1.5V4h-2.5" fill="none" stroke="currentColor" stroke-width="1.3"/>
        </svg>
      </button>
      <span class="divider" aria-hidden="true"></span>
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
      <button class="badge pending-badge" :class="pending > 0 ? 'warn' : 'dim'"
        id="badge-pending" :disabled="!pending"
        title="待审批积压数（第四批 4.2 通知中心简化形态）：10s 轮询，>0 时点击直接跳首个待审批事件"
        @click="jumpPending">
        待审批 <b class="mono">{{ pending }}</b>
      </button>
      <span class="badge outcome-chip" :class="store.outcomeChip.cls" id="badge-outcome">
        {{ store.outcomeChip.text }}
      </span>
    </div>
  </header>
</template>

<style scoped>
.pending-badge {
  cursor: pointer; background: none; border: 1px solid var(--line);
  font: 12px var(--sans); padding: 3px 8px; border-radius: 2px; color: var(--txt);
}
.pending-badge[disabled] { opacity: .55; cursor: default; }
.pending-badge b { font-family: var(--mono); }
.topbar {
  display: flex; align-items: center; justify-content: space-between; gap: 16px;
  padding: 0 16px; height: 48px; flex-wrap: nowrap;
  border-bottom: 1px solid var(--line);
  background: var(--panel);
}
.brand { display: flex; align-items: center; gap: 10px; flex-shrink: 0; }
.logo { width: 18px; height: 18px; flex-shrink: 0; }
.brand h1 {
  font-size: 14px; font-weight: 600; letter-spacing: .3px; color: var(--txt);
}
/* 环境选择器徽章：Grafana org picker 形态（小圆点 + 文本 + 边框） */
.env-badge {
  display: inline-flex; align-items: center; gap: 6px;
  font: 11px/1 var(--mono); letter-spacing: .5px; padding: 4px 9px;
  border: 1px solid var(--line); border-radius: 2px;
  background: var(--panel-2); color: var(--dim); cursor: default;
}
.env-dot { width: 7px; height: 7px; border-radius: 50%; background: var(--ok); }
.controls { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; justify-content: flex-end; min-width: 0; }
/* Grafana 顶栏假控件：工具条按钮形态（扁平、1px 边框、2px 圆角） */
.fake-ctl {
  display: inline-flex; align-items: center; gap: 6px;
  font: 12px var(--mono); color: var(--dim); cursor: default;
  padding: 6px 10px; border: 1px solid var(--line); border-radius: 2px;
  background: var(--panel-2); user-select: none;
}
.fake-ctl.icon-only { padding: 6px 8px; }
.ctl-icon { width: 12px; height: 12px; }
.caret { opacity: .7; }
.divider { width: 1px; height: 20px; background: var(--line); margin: 0 2px; }
@media (max-width: 900px) {
  .topbar { height: auto; padding: 10px 16px; flex-wrap: wrap; }
}
</style>
