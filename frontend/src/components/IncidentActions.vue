<script setup>
/**
 * 工具条：一键注入剧本+创建事件链（seed-logs → POST /api/incidents → 载入重放），
 * 手动创建事件对话框（el-dialog，替代旧版手写 modal，缺省值与旧版输入框同款）。
 */
import { ref, reactive } from 'vue';
import { useIncidentStore } from '../stores/incident';

const store = useIncidentStore();
const dialogVisible = ref(false);
const form = reactive({
  service: 'judge-service',
  summary: '队列堆积告警：queueDepth 超阈值且持续爬升，多容器 timeout killed',
});

async function seed() {
  await store.seedScenario();
}
async function create() {
  dialogVisible.value = false;
  await store.manualCreate({ service: form.service, summary: form.summary });
}
</script>

<template>
  <button class="btn primary" :disabled="store.busy" @click="seed">一键注入故障剧本</button>
  <button class="btn ghost" @click="dialogVisible = true">＋ 手动创建事件</button>

  <el-dialog v-model="dialogVisible" title="手动创建事件" width="min(460px, 92vw)" append-to-body>
    <div class="form">
      <label>服务名
        <el-input v-model="form.service" spellcheck="false" />
      </label>
      <label>告警摘要
        <el-input v-model="form.summary" type="textarea" :rows="3" />
      </label>
    </div>
    <template #footer>
      <button class="btn ghost" @click="dialogVisible = false">取消</button>
      <button class="btn primary" :disabled="store.busy" @click="create">创建并诊断</button>
    </template>
  </el-dialog>
</template>

<style scoped>
.btn {
  cursor: pointer; border-radius: 2px; padding: 7px 14px; font-size: 12.5px;
  color: var(--txt); border: 1px solid var(--line); background: var(--panel-3);
  transition: background .15s ease, border-color .15s ease;
}
.btn:disabled { opacity: .35; cursor: not-allowed; }
.btn.primary { border: none; color: #fff; font-weight: 600; background: var(--info); }
.btn.primary:not(:disabled):hover { background: #3f83e8; }
.btn.ghost:not(:disabled):hover { border-color: var(--dim); }
.form { display: grid; gap: 12px; }
.form label { display: block; font-size: 12px; color: var(--dim); }
</style>
