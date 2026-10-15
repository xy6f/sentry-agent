<script setup>
/**
 * 右栏：节点时间线竖流。数据源为 store.timeline（SSE step_done 增量 append /
 * 轮询降级增量 / 历史重放按节奏追加，三条路径同一条渲染通道，(at|node) 去重在 store）。
 * props 接收数组以满足组件化契约；自动滚到底部模拟旧版 box.scrollTop 行为。
 */
import { ref, watch, nextTick } from 'vue';
import { NODE_LABEL } from '../lib/topology';
import { fmtTime } from '../lib/parsers';

const props = defineProps({
  entries: { type: Array, default: () => [] },
  src: { type: String, default: '—' },
});

const box = ref(null);
watch(() => props.entries.length, async () => {
  await nextTick();
  if (box.value) box.value.scrollTop = box.value.scrollHeight;
});
</script>

<template>
  <div class="glass col col-right" id="col-right">
    <h2 class="col-title">节点时间线 <span class="mono dim">{{ src }}</span></h2>
    <div class="stream tl" ref="box">
      <p v-if="!entries.length" class="placeholder">SSE 实时增量（step_done），断流自动降级轮询。</p>
      <div v-for="(e, i) in entries" :key="e.at + '|' + e.node + '|' + i" class="tl-item" :class="'nd-' + e.node">
        <div class="tl-time">{{ fmtTime(e.at) }}</div>
        <span class="tl-dot"></span>
        <div>
          <div class="tl-node">{{ e.node }} · {{ NODE_LABEL[e.node] || '' }}</div>
          <div class="tl-digest">{{ e.digest }}</div>
        </div>
      </div>
    </div>
  </div>
</template>
