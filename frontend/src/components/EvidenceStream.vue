<script setup>
/**
 * 左栏：证据卡片流 + 假设/判定卡片流。
 * 解析规则（[首轮]/[补充] 前缀、来源徽章、假设行 "N. 假设 | 验证方法"、
 * verdict 行 "N | 成立|不成立 | 依据" 序号对齐挂绿框/灰卡）全部走 lib/parsers.js，
 * 与旧版 renderLeft/evidenceCard 逐条一致。
 */
import { computed } from 'vue';
import { useIncidentStore } from '../stores/incident';
import { parseEvidence, parseHypotheses } from '../lib/parsers';

const store = useIncidentStore();

const evidences = computed(() => (store.snapshot?.evidences || []).map(parseEvidence));
const hypo = computed(() => store.snapshot ? parseHypotheses(store.snapshot) : { cards: [], extra: [] });
</script>

<template>
  <div class="glass col" id="col-left">
    <h2 class="col-title">证据 <span class="mono dim">{{ evidences.length ? '×' + evidences.length : '' }}</span>· 假设</h2>
    <div class="stream">
      <p v-if="!evidences.length" class="placeholder">选中事件后展示证据卡片流。<br>点击「一键注入故障剧本」开始。</p>
      <div v-for="(e, i) in evidences" :key="i" class="card">
        <div class="src-badges">
          <span class="tag" :class="e.first ? 'gen' : 'sec'">{{ e.first ? '首轮取证' : '补充取证' }}</span>
          <span class="tag src">{{ e.src }}</span>
          <span v-if="e.byMethod" class="tag src">BY-VERIFY-METHOD</span>
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
          <span v-if="c.verdict" class="tag" :class="c.verdict.held ? 'hold' : 'noh'">
            {{ c.verdict.held ? '判定成立 ✓' : '判定不成立 ✕' }}
          </span>
        </div>
        <p class="body">{{ c.claim }}{{ c.method ? `\n验证方法：${c.method}` : '' }}</p>
        <p v-if="c.verdict" class="body">判定依据：{{ c.verdict.why }}</p>
      </div>
      <div v-if="hypo.extra.length" class="card"><p class="body">{{ hypo.extra.join('\n') }}</p></div>
    </div>
  </div>
</template>
