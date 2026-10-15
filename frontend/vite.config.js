import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';

/**
 * sentry-agent 作战大屏 · Vite 配置
 *
 * - 端口 5176、strictPort：多前端并行演示时端口不漂移。
 * - 代理 '/api' → http://localhost:8084：浏览器同源访问 /api/**，免 CORS。
 * - SSE 透传说明：GET /api/incidents/{id}/stream 是 text/event-stream 长连接。
 *   Vite 的 proxy 基于 node-http-proxy，默认就是逐块流式转发（不做缓冲、不聚合 body），
 *   且本项目前端刻意不开 vite-plugin-compression 之类的中间件——SSE 帧到达即透传。
 *   要点：
 *     1) 不要给该路径配 selfHandleResponse / 响应压缩，否则 event 会被攒住；
 *     2) changeOrigin=false 即可（后端 CorsConfig 已放行），保持 Host 原样利于
 *        Spring SseEmitter 无关；超时由后端 sentry.sse.timeout-ms(120s) 与前端
 *        断流降级轮询兜底，proxy 本身不掐流。
 *   实测：dev 下 curl http://localhost:5176/api/incidents/{id}/stream 能逐事件收到
 *   event:step_done / event:done，即代理未缓冲。
 */
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5176,
    strictPort: true,
    proxy: {
      '/api': {
        target: 'http://localhost:8084',
        changeOrigin: false,
        ws: false, // SSE 走普通 HTTP 长连接，不需要 websocket 升级
      },
    },
  },
  build: {
    outDir: 'dist',
    chunkSizeWarningLimit: 1200, // element-plus 全量引入，放宽警告
  },
});
