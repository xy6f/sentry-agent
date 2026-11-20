<script setup>
/**
 * 左栏：证据卡片流 + 假设/判定卡片流。
 * 解析规则（[首轮]/[补充]/[补充证据-定向] 前缀、来源徽章、结构化假设 JSON 行
 * （cause/confidence/verifyAction）与逐行 JSON 判定（index/conclusion/reason）、
 * 旧文本行兜底、序号对齐挂绿框/灰卡）全部走 lib/parsers.js，
 * 与后端 HypothesisParser 保持同一"JSON 优先、旧文本兜底"的解析次序。
 */
import { computed } from 'vue';
import { useIncidentStore } from '../stores/incident';
import { parseEvidence, parseHypotheses } from '../lib/parsers';

const store = useIncidentStore();

const evidences = computed(() => (store.snapshot?.evidences || []).map(parseEvidence));
const hypo = computed(() => store.snapshot ? parseHypotheses(store.snapshot) : { cards: [], extra: [] });

// 判定徽章文案：成立 ✓ / 不成立 ✕ / 存疑 ?（旧版只有两态，存疑会误标"不成立"）
const verdictBadge = (v) => {
  if (!v) return '';
  if (v.conclusion === '成立') return '判定成立 ✓';
  if (v.conclusion === '存疑') return '判定存疑 ?';
  return '判定不成立 ✕';
};
</script>

<template>
  <div class="glass col" id="col-left">
    <h2 class="col-title">证据 <span class="mono dim">{{ evidences.length ? '×' + evidences.length : '' }}</span>· 假设</h2>
    <div class="stream">
      <p v-if="!evidences.length" class="placeholder">选中事件后展示证据卡片流。<br>点击「一键注入故障剧本」开始。</p>
      <div v-for="(e, i) in evidences" :key="i" class="card">
        <div class="src-badges">
          <span class="tag" :class="e.first ? 'gen' : 'sec'">{{ e.first ? '首轮取证' : '补充取证' }}</span>
          <span v-if="e.targeted" class="tag sec">[定向取证]</span>
          <span class="tag src-plain">[{{ e.src.toLowerCase() }}]</span>
          <span v-if="e.byMethod" class="tag src-plain">[by-verify-method]</span>
        </div>
        <p class="body">{{ e.text }}</p>
      </div>
    </div>

    <h2 class="col-title mt">假设与判定</h2>
    <div class="stream">
      <p v-if="!hypo.cards.length" class="placeholder">假设与判定将在 hypothesize / verify 节点产出后出现。</p>
      <div
        v-for="c in hypo.cards" :key="c.idx"
        class="card" :class="c.verdict ? (c.verdict.held ? 'hypo-held' : 'hypo-dead') : ''"
      >
        <div class="src-badges">
          <span class="tag gen">假设 {{ c.idx }}</span>
          <span v-if="c.confidence != null" class="tag src-plain">置信度 {{ c.confidence }}%</span>
          <span v-if="c.verdict" class="tag" :class="c.verdict.held ? 'hold' : 'noh'">
            {{ verdictBadge(c.verdict) }}
          </span>
        </div>
        <p class="body">{{ c.claim }}{{ c.method ? `\n验证方法：${c.method}` : '' }}</p>
        <p v-if="c.verdict" class="body">判定依据：{{ c.verdict.why }}</p>
      </div>
      <div v-if="hypo.extra.length" class="card"><p class="body">{{ hypo.extra.join('\n') }}</p></div>
    </div>
  </div>
</template>
