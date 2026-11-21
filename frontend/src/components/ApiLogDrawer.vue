<script setup>
/**
 * API 日志抽屉（el-drawer 替代旧版手写抽屉）：全部请求流水、API_BASE 改写入口。
 * 默认空串 = 同源走 vite proxy（5176 → 8084）；填绝对地址即直连后端（CorsConfig 放行）。
 * 第四批 4.1：新增管理密钥输入——后端 sentry.security.admin-key 非空时，
 * 审批/跑批请求自动附带 X-Admin-Key（受保护路径判定见 stores/incident.request）。
 */
import { ref, watch, nextTick } from 'vue';
import { ElMessage } from 'element-plus';
import { useApiLogStore } from '../stores/apiLog';
import { useIncidentStore } from '../stores/incident';

const apiLog = useApiLogStore();
const incident = useIncidentStore();
const baseInput = ref(apiLog.apiBase);
const keyInput = ref(apiLog.adminKey);
const pre = ref(null);

watch(() => apiLog.lines.length, async () => {
  await nextTick();
  if (pre.value) pre.value.scrollTop = pre.value.scrollHeight;
});

function saveBase() {
  apiLog.setApiBase(baseInput.value);
  incident.request('GET', '/api/incidents').catch(() => {}); // 立即探测 + 留痕（列表端点恒 200，不再制造 404 console error）
}

// 第四批 4.1：管理密钥存 localStorage；真实审批由受保护路径自动带头（探测不带钥）
function saveKey() {
  apiLog.setAdminKey(keyInput.value);
  ElMessage.success(apiLog.adminKey
    ? '管理密钥已保存（审批/跑批将自动携带 X-Admin-Key）'
    : '管理密钥已清空（后端鉴权关闭时留空即可）');
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
    <div class="drawer-head">
      <label class="api-base">ADMIN_KEY
        <input v-model="keyInput" type="password" spellcheck="false" @change="saveKey"
          placeholder="后端配置了 admin-key 时填写；本地演示留空" />
      </label>
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
  font: 12px var(--mono); color: var(--dim); background: var(--panel);
  border: 1px solid var(--line); border-radius: 2px; padding: 8px 13px;
}
.drawer-tab:hover { color: var(--txt); border-color: var(--dim); }
.mono { font-family: var(--mono); }
.drawer-head { display: flex; justify-content: space-between; align-items: center; gap: 10px; margin-bottom: 10px; }
</style>
