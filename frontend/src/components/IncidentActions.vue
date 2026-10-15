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
  <button class="btn primary" :disabled="store.busy" @click="seed">⚡ 一键注入故障剧本</button>
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
  cursor: pointer; border-radius: 10px; padding: 9px 16px; font-size: 13.5px;
  color: var(--txt); border: 1px solid var(--line); background: rgba(12,24,44,.8);
  transition: transform .12s ease, opacity .12s ease, box-shadow .2s ease;
}
.btn:hover { transform: translateY(-1px); }
.btn:disabled { opacity: .35; cursor: not-allowed; transform: none; }
.btn.primary {
  border: none; color: #041018; font-weight: 700;
  background: linear-gradient(90deg, var(--cyan), var(--blue));
  box-shadow: 0 4px 18px rgba(34,211,238,.35);
}
.btn.primary:hover { box-shadow: 0 6px 24px rgba(34,211,238,.55); }
.btn.ghost:hover { border-color: rgba(34,211,238,.5); }
.form { display: grid; gap: 12px; }
.form label { display: block; font-size: 12px; color: var(--dim); }
</style>
