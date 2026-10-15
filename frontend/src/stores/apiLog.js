import { defineStore } from 'pinia';
import { ref, computed } from 'vue';
import { fmtTime, short } from '../lib/parsers';

const BASE_KEY = 'sentr…base';

/**
 * API 日志抽屉 store（旧版 apiLines/logApi/setApiBadge/API_BASE 输入的原生实现迁入）。
 * apiBase 默认为空串 = 同源相对路径，走 vite proxy '/api' → 8084；
 * 也可在抽屉里改成绝对地址（如 http://localhost:8084）直连后端（后端 CorsConfig 放行）。
 */
export const useApiLogStore = defineStore('apiLog', () => {
  const lines = ref([]);            // {t, text, cls}  cls: '' | 'ok' | 'err'
  const drawerOpen = ref(false);
  const apiBase = ref(localStorage.getItem(BASE_KEY) ?? '');
  const apiOk = ref(null);          // null=探测中

  const count = computed(() => lines.value.length);

  function log(text, cls = '') {
    lines.value.push({ t: fmtTime(Date.now()), text, cls });
    if (lines.value.length > 200) lines.value.shift();
  }

  function setApiBase(v) {
    apiBase.value = String(v || '').trim().replace(/\/+$/, '');
    localStorage.setItem(BASE_KEY, apiBase.value);
  }

  function setApiBadge(ok) { apiOk.value = ok; }

  /** 连通性探测（旧版 probeApi 打 GET /api/incidents/0——该 id 必不存在返回 404，
      浏览器网络层必然记一条 console error，大屏"console 无 error"验收被它污染。
      改打契约内的列表端点 GET /api/incidents（恒 200），能连上即在线的语义不变。） */
  async function probeApi() {
    try {
      await fetch(apiBase.value + '/api/incidents', { method: 'GET' });
      setApiBadge(true);
    } catch {
      setApiBadge(false);
    }
  }

  return { lines, count, drawerOpen, apiBase, apiOk, log, setApiBase, setApiBadge, probeApi, short };
});
