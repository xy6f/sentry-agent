/**
 * SSE 读取（升级版实现：fetch + ReadableStream 增量解析，替代旧版 EventSource）。
 *
 * 为什么不再用 EventSource：
 *  - EventSource 无法携带自定义头、断线语义粗糙，且重连会"整条重订阅"；
 *  - fetch+ReadableStream 拿到的是原始字节流，按 SSE 帧规范（\n\n 分帧、
 *    event:/data: 字段行）逐帧解析，天然支持"增量 append"式渲染，
 *    断开即 reader done，降级判定更干净。
 *
 * 事件契约（后端 IncidentController.stream，命名事件）：
 *  - step_done data={at, node, digest}   节点完成一步
 *  - done      data={outcome}            终态收流
 *  - error     data={message}            服务端主动报错（如事件不存在）——只发一次
 *
 * 代理说明：dev 下该请求经 vite proxy '/api' 透传，node-http-proxy 默认逐块流式
 * 转发不缓冲（见 vite.config.js 注释），所以这里读到的 chunk 就是真实帧节奏。
 *
 * onDisconnect：连接建立失败、流在非 done/error 处结束（含服务端 120s 超时主动收流）、
 * 网络中断——一律回调，由调用方降级为轮询（见 stores/incident.js 的注释）。
 */
export async function openIncidentStream({ id, base = '', signal, onStep, onDone, onServerError, onDisconnect }) {
  const url = `${base}/api/incidents/${id}/stream`;
  let reader;
  let finished = false; // 收到 done / error 即正常终结，不再按"断流"处理

  const dispatchFrame = (raw) => {
    let ev = 'message';
    const dataLines = [];
    for (const line of raw.split(/\r?\n/)) {
      if (line.startsWith('event:')) ev = line.slice(6).trim();
      else if (line.startsWith('data:')) dataLines.push(line.slice(5).replace(/^ /, ''));
      // id:/retry:/注释行（以 ':' 开头）本场景忽略
    }
    if (!dataLines.length) return;
    let payload;
    try { payload = JSON.parse(dataLines.join('\n')); } catch { return; }
    if (ev === 'step_done') { onStep && onStep(payload); }
    else if (ev === 'done') { finished = true; onDone && onDone(payload); }
    else if (ev === 'error') { finished = true; onServerError && onServerError(payload); }
  };

  try {
    const resp = await fetch(url, { headers: { Accept: 'text/event-stream' }, signal });
    if (!resp.ok || !resp.body) throw new Error(`SSE HTTP ${resp.status}`);
    reader = resp.body.getReader();
    const decoder = new TextDecoder('utf-8');
    let buf = '';
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      buf += decoder.decode(value, { stream: true });
      // 兼容 \r\n 分帧：统一按空行切
      let i;
      while ((i = buf.search(/\r?\n\r?\n/)) >= 0) {
        const frame = buf.slice(0, i);
        buf = buf.slice(i).replace(/^\r?\n\r?\n/, '');
        if (frame.trim()) dispatchFrame(frame);
      }
    }
  } catch (e) {
    if (e && e.name === 'AbortError') return; // 主动停止：不算断流
    if (!finished) { onDisconnect && onDisconnect(e); return; }
  }
  if (!finished && signal && !signal.aborted) onDisconnect && onDisconnect(new Error('流结束未见 done'));
}
