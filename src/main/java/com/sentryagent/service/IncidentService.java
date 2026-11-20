package com.sentryagent.service;

import com.sentryagent.graph.IncidentState;
import com.sentryagent.tool.ApprovalRegistry;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.CompiledGraph;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 事件编排服务：创建事件 -> 驱动图 -> 保存终态快照 -> 支持"审批后续跑"。
 *
 * <p>W1 的续跑策略是"幂等重放"（各节点对已有产出直接跳过，从头再走一遍图，
 * 模型侧几乎零消耗因为跳过的节点不发起推理）；W3 升级为 LangGraph4j 的
 * interrupt + checkpoint resume，真·断点续跑——接口形态不变，只换实现，
 * 注释里把这个演进路线钉死，面试讲演进就用它。</p>
 *
 * <p>可观测性接线（计划书 3.1 Should"SSE 进度"的数据源）：图每个节点完成时
 * IncidentGraphFactory 的 timed 装饰器把 (时间, 节点, outcome 摘要) 记入
 * NodeProgressListener 的事件时间线；本服务的 get() 在返回详情快照时把
 * timeline 字段并入，SSE 端点（IncidentController.stream）补发与轮询新增
 * 读的也是同一个登记处——三条消费路径共享一份事实源，不各记各的账。</p>
 *
 * <p>读路径分层（不足清单第 1 项"读路径落库"）：<b>运行中事件的事实源是内存
 * Map</b>（图重放语义要求完整 State 通道），<b>重启后历史事件的事实源是
 * t_incident/t_graph_step</b>（IncidentPersistence 异步镜像的底账）——
 * get()/list() 按"内存命中优先、未命中查 DB、DB 挂了降级"分层，接口 JSON
 * 形状两条路径一致（前端零改动）。AWAITING_APPROVAL 事件重启后可从 DB 恢复
 * 展示，但"重启续跑"不在本轮（checkpoint resume 演进路线见上）。</p>
 *
 * <p>入口防线两层（任务 A，不足清单第 5 项，都收口在 createAndRun 最前面）：</p>
 * <ol>
 *   <li><b>进行中去重</b>（语义闸门）：同 service 且 outcome∈{RUNNING,
 *       AWAITING_APPROVAL} 的进行中事件已存在时拒绝（BUSY_DUPLICATE，快照带
 *       conflictWith=进行中事件号）。与 Watchman 的 cooldown（规则键冷却，
 *       防巡检周期内自我 DDoS）构成<b>双层防暴</b>：cooldown 管"同一告警源
 *       短时间内别重复喊"（时间维度、规则键粒度、事件终不终态都拦），
 *       去重管"同服务还有没诊断完/等审批的事件就别再造单"（状态维度、服务
 *       粒度、事件一到终态即解除）——两层各有盲区（cooldown 过期后事件可能
 *       还在等审批；非 Watchman 来源没有冷却），叠起来才闭环。</li>
 *   <li><b>并发上限</b>（资源闸门）：Semaphore(sentry.incident.max-concurrent，
 *       默认 3)。tryAcquire 失败 = 配额用尽，<b>不排队直接拒绝</b>
 *       （outcome=REJECTED_BUSY 的快照）——运维系统宁可拒收也不堆积：排队只会
 *       把故障告警拖成"延迟处置"，还让上游（Watchman/调用方）误以为事件已被
 *       受理。配额语义"谁拿谁还"：createAndRun 与 approve（<b>审批续跑也算
 *       占用</b>——续跑期间事件同样占着一个诊断工位）两条写路径各自
 *       tryAcquire、在同一个 try/finally 里 release，成功/FAILED/图抛异常
 *       三个出口都恰好归还一次；approve 时配额用尽则原快照带回 busy 提示、
 *       不签令牌不重跑（审批窗口保持打开，稍后重试即可）。</li>
 * </ol>
 *
 * <p>两类拒绝都落一张 REJECTED_* 或 BUSY_DUPLICATE 快照（发号、进仓、落库镜像，
 * 但<b>不进图、不占配额、无时间线</b>——没有诊断发生就不得伪造诊断痕迹）：
 * 拒收本身必须可回查可审计，这是"宁可拒收也不堆积"的另一半纪律。</p>
 */
@Slf4j
@Service
public class IncidentService {

    private final CompiledGraph<IncidentState> graph;
    private final ApprovalRegistry approvalRegistry;
    private final NodeProgressListener progressListener;
    /** 通知监听器（可选依赖：notify 关闭时为 null，跃迁点判空跳过） */
    private final com.sentryagent.notify.NotificationListener notificationListener;
    /** Phase 4 事件落库（可选依赖：persistence.enabled=false 时 provider 无 bean，纯内存形态） */
    private final org.springframework.beans.factory.ObjectProvider<com.sentryagent.persist.IncidentPersistence> persistenceProvider;
    /**
     * 读路径落库（可选依赖，与 persistence 同开关 sentry.persistence.enabled）：
     * t_incident/t_graph_step 的读侧组装器。运行中事件不经过它——
     * 事实源切换边界见 get()/list() 注释。
     */
    private final org.springframework.beans.factory.ObjectProvider<com.sentryagent.persist.IncidentQueryService> queryProvider;

    /**
     * 并发上限闸门（任务 A）：permits = sentry.incident.max-concurrent（默认 3）。
     * createAndRun 与 approve（续跑也算占用）两条写路径共用，"谁拿谁还"——
     * 获取点在各写路径入口，归还点在调用方的 finally（成功/FAILED/异常三个
     * 出口都恰好 release 一次）。终态事件不再占用"诊断工位"。
     */
    private final Semaphore diagnosisSlots;

    /** 进行中去重开关（任务 A）：sentry.incident.dedupe.enabled，默认 true */
    private final boolean dedupeEnabled;

    /**
     * 内存事件仓：**运行中事件的事实源**（图重放语义要求完整 State 通道）。
     * 终态/不存在事件的事实源在 t_incident（重启后内存清空但 DB 有底账），
     * 读路径分层见 get()/list()。
     */
    private final Map<Long, Map<String, Object>> incidents = new ConcurrentHashMap<>();
    private final AtomicLong idGen = new AtomicLong(1);

    /** idGen 播种开关（测试 profile 关闭，保持"context 首个事件 id=1"的既有内存语义） */
    @Value("${sentry.persistence.seed-id-from-db:true}")
    private boolean seedIdFromDbEnabled;

    /**
     * 兼容构造（既有单测 ApprovalExpiredEscalateTest 直接 new 的 6 参形态不变）：
     * 并发上限默认 3、进行中去重默认开。
     */
    public IncidentService(CompiledGraph<IncidentState> graph,
                           ApprovalRegistry approvalRegistry,
                           NodeProgressListener progressListener,
                           com.sentryagent.notify.NotificationListener notificationListener,
                           org.springframework.beans.factory.ObjectProvider<com.sentryagent.persist.IncidentPersistence> persistenceProvider,
                           org.springframework.beans.factory.ObjectProvider<com.sentryagent.persist.IncidentQueryService> queryProvider) {
        this(graph, approvalRegistry, progressListener, notificationListener,
                persistenceProvider, queryProvider, 3, true);
    }

    /**
     * 全参构造（Spring 唯一装配入口，@Autowired 消除双构造的歧义）：
     * maxConcurrent = sentry.incident.max-concurrent（默认 3，&lt;=0 按 1 兜底防
     * Semaphore 非法参数）；dedupeEnabled = sentry.incident.dedupe.enabled（默认 true）。
     * 评测 harness（eval 包）用同款构造按剧本参数装配全内存的独立实例。
     */
    @org.springframework.beans.factory.annotation.Autowired
    public IncidentService(CompiledGraph<IncidentState> graph,
                           ApprovalRegistry approvalRegistry,
                           NodeProgressListener progressListener,
                           com.sentryagent.notify.NotificationListener notificationListener,
                           org.springframework.beans.factory.ObjectProvider<com.sentryagent.persist.IncidentPersistence> persistenceProvider,
                           org.springframework.beans.factory.ObjectProvider<com.sentryagent.persist.IncidentQueryService> queryProvider,
                           @Value("${sentry.incident.max-concurrent:3}") int maxConcurrent,
                           @Value("${sentry.incident.dedupe.enabled:true}") boolean dedupeEnabled) {
        this.graph = graph;
        this.approvalRegistry = approvalRegistry;
        this.progressListener = progressListener;
        this.notificationListener = notificationListener;
        this.persistenceProvider = persistenceProvider;
        this.queryProvider = queryProvider;
        this.diagnosisSlots = new Semaphore(Math.max(maxConcurrent, 1));
        this.dedupeEnabled = dedupeEnabled;
    }

    /**
     * 启动播种：idGen 从 DB 历史最大事件号之上继续发号（开关
     * sentry.persistence.seed-id-from-db，测试 profile 关闭——现有端到端用例
     * 依赖"context 首个事件 id=1"的内存语义，见 application-test.yml）。
     * 修复根因：重启后 idGen 归 1，而 t_incident 已有同号底账——新建事件
     * saveIncident 是 insert-or-update，会把历史行整行覆盖，审计底账被冲掉。
     * DB 不可达/落库关闭时保持从 1 起（纯内存形态无所谓撞号），降级只记日志。
     */
    @jakarta.annotation.PostConstruct
    void seedIdFromDb() {
        if (!seedIdFromDbEnabled) {
            return;
        }
        com.sentryagent.persist.IncidentQueryService q = queryProvider.getIfAvailable();
        if (q != null) {
            try {
                long max = q.maxIncidentId();
                if (max > 0) {
                    idGen.set(max + 1);
                    log.info("idGen seeded from DB: next incident id = {}", max + 1);
                }
            } catch (Exception e) {
                log.warn("seed idGen from DB failed (degraded, next id = 1): {}", e.getMessage());
            }
        }
    }

    /**
     * 建事件入口（两层防线的落点，顺序"先去重后占配额"——重复告警不该消耗
     * 稀缺的并发名额）：
     * <ol>
     *   <li>进行中去重：同 service 且进行中（RUNNING/AWAITING_APPROVAL）的事件
     *       存在 → 拒绝（BUSY_DUPLICATE + conflictWith），双层防暴语义见类注释。
     *       终态（CLOSED/ESCALATED/FAILED/REJECTED_*）不算进行中——终态后去重
     *       自动解除，同服务可再次建单；</li>
     *   <li>并发上限：tryAcquire 失败 = 配额用尽 → 拒绝（REJECTED_BUSY），不排队。
     *       拿到配额才发号进图，finally 归还。</li>
     * </ol>
     * 两类拒绝都落 REJECTED_* 或 BUSY_DUPLICATE 快照：发号、进仓、落库镜像，但
     * 不进图、不占配额、无时间线（没有诊断发生就不得伪造诊断痕迹）——
     * 运维系统宁可拒收也不堆积，且拒收本身可回查可审计。
     */
    public Map<String, Object> createAndRun(String service, String alert) {
        String svc = service == null ? "" : service;
        // ── 第一道：进行中去重（状态维度，服务粒度；开关 sentry.incident.dedupe.enabled） ──
        if (dedupeEnabled) {
            Long runningId = findInFlightId(svc);
            if (runningId != null) {
                long id = idGen.getAndIncrement();
                Map<String, Object> rejected = CreateRejection.snapshot(
                        id, svc, alert, CreateRejection.BUSY_DUPLICATE, runningId);
                incidents.put(id, rejected);
                persistSnapshot(id, rejected);
                log.info("incident {} rejected (BUSY_DUPLICATE): service={} in-flight={}",
                        id, svc, runningId);
                return rejected;
            }
        }
        // ── 第二道：并发上限（资源维度，全局粒度），不排队直接拒 ──
        if (!diagnosisSlots.tryAcquire()) {
            long id = idGen.getAndIncrement();
            Map<String, Object> rejected = CreateRejection.snapshot(
                    id, svc, alert, CreateRejection.REJECTED_BUSY, -1);
            incidents.put(id, rejected);
            persistSnapshot(id, rejected);
            log.info("incident {} rejected (REJECTED_BUSY): service={} max-concurrent reached", id, svc);
            return rejected;
        }
        long id = idGen.getAndIncrement();
        Map<String, Object> input = new HashMap<>();
        input.put(IncidentState.INCIDENT_ID, id);
        input.put(IncidentState.SERVICE, svc);
        input.put(IncidentState.ALERT, alert);
        notify(id, "CREATED");
        try {
            return run(id, input);
        } finally {
            diagnosisSlots.release();   // "谁拿谁还"：本路径 acquire 的配额在此归还
        }
    }

    /**
     * 扫描同服务的"进行中"事件号（RUNNING/AWAITING_APPROVAL 两种）：
     * RUNNING 是图推进中的瞬间态，AWAITING_APPROVAL 是停在审批闸口的常态——
     * 后者是现实中占比最长的"进行中"形态，去重必须覆盖它，否则审批挂起期间
     * 同服务告警会重复建单，双层防暴就漏了一层。找到即返回最小事件号
     * （审计视角里最早的那个是"正主"），没有进行中事件返回 null。
     * 实现是内存仓线性扫：演示规模（个位数并发）下 O(n) 扫 ConcurrentHashMap
     * 无锁安全，比"另维护一张进行中索引"不易腐烂（索引与快照的双写一致性
     * 才是真坑）。只扫内存仓——运行中事件的事实源就是内存（类注释分层读）。
     */
    private Long findInFlightId(String service) {
        long best = -1;
        for (Map.Entry<Long, Map<String, Object>> e : incidents.entrySet()) {
            Map<String, Object> snap = e.getValue();
            String outcome = String.valueOf(snap.getOrDefault(IncidentState.OUTCOME, ""));
            boolean inFlight = "RUNNING".equals(outcome) || "AWAITING_APPROVAL".equals(outcome);
            if (inFlight && service.equals(String.valueOf(
                    snap.getOrDefault(IncidentState.SERVICE, "")))) {
                long id = e.getKey();
                if (best < 0 || id < best) {
                    best = id;
                }
            }
        }
        return best < 0 ? null : best;
    }

    /**
     * 审批：给事件签发一次性令牌，并驱动图从"审批闸口"继续；终态事件幂等返回现快照。
     *
     * <p>续跑也算占用（任务 A）：approve 复用 run() 驱动图，续跑期间同样占用
     * 一个诊断工位——入口 tryAcquire、finally release，与 createAndRun 共享同一
     * 份配额。配额用尽时不排队：原快照加 reason 提示原样返回（outcome 仍是
     * AWAITING_APPROVAL、不签令牌、不重跑图），审批窗口保持打开，稍后重试即可
     * ——与 createAndRun"宁可拒收也不堆积"同一条纪律在审批面的延伸。</p>
     */
    public Map<String, Object> approve(long incidentId, boolean approved) {
        Map<String, Object> snapshot = incidents.get(incidentId);
        if (snapshot == null) {
            throw new IllegalArgumentException("事件不存在: " + incidentId);
        }
        // 终态闸口（GUI 测试遗留观察1 的修复）：对已终结事件（CLOSED/ESCALATED）
        // 重复下决策，原实现会先给 ApprovalRegistry 再签一枚永不消费的令牌——
        // 图重放时 execute 已过、令牌无人 consume，属纯泄漏。
        // 幂等语义：终态不可再翻转，approve/reject 都原样返回现快照，不签发、不重跑。
        String outcome = String.valueOf(snapshot.getOrDefault(IncidentState.OUTCOME, ""));
        if ("CLOSED".equals(outcome) || "ESCALATED".equals(outcome)) {
            log.info("incident {} already terminal ({}), decision approved={} ignored (idempotent)",
                    incidentId, outcome, approved);
            return incidents.get(incidentId);
        }
        // 续跑占用配额：拿不到就原样返回（不签令牌不重跑），语义见方法注释
        if (!diagnosisSlots.tryAcquire()) {
            Map<String, Object> busy = new HashMap<>(snapshot);
            busy.put("reason", "并发配额已满（sentry.incident.max-concurrent），审批续跑暂缓："
                    + "不排队，请稍后重试");
            log.info("approve {} deferred (REJECTED_BUSY): max-concurrent reached", incidentId);
            return busy;
        }
        try {
            Map<String, Object> input = new HashMap<>(snapshot);
            input.put(IncidentState.OUTCOME, "RUNNING");   // 清掉等待标记
            Map<String, Object> result;
            if (approved) {
                input.put(IncidentState.APPROVAL, approvalRegistry.issue(incidentId));
            } else {
                input.put(IncidentState.APPROVAL, "");
                // 拒绝：直接终结（W2 可改为带理由回到 plan 重出方案）。
                // GUI 测试修复（P1）：原实现用 Map.of 整体替换快照，evidences/hypotheses/
                // service/alert/plan 全部丢失——前端 ESCALATED 事件左栏因此空白、
                // 历史行显示 "?"。正确姿势：在既有快照上合并写回，只改 outcome/report 两键。
                Map<String, Object> rejected = new HashMap<>(snapshot);
                rejected.put(IncidentState.OUTCOME, "ESCALATED");
                rejected.put(IncidentState.REPORT, "人工否决自动处置，转人工处置");
                incidents.put(incidentId, rejected);
                notify(incidentId, "ESCALATED");
                return incidents.get(incidentId);
            }
            result = run(incidentId, input);
            // 过期令牌改判（不足清单第 2 项，approve 写路径的最小改动）：
            // approve 的 false 现语义是"无令牌"（approve=false 分支人工否决），不能混用——
            // 真正的"过期"只发生在图内 execute 消费令牌时（工具 REFUSED 文案带 EXPIRED 标记，
            // 见 EvidenceTools.refusedText）。这里识别标记后把事件改判 ESCALATED：
            // 审批窗口已关≠人工否决≠正常闭环，须如实转人工。
            if (tokenExpiredDuringExecute(result)) {
                Map<String, Object> expired = new HashMap<>(result);
                expired.put(IncidentState.OUTCOME, "ESCALATED");
                expired.put(IncidentState.REPORT, "审批令牌过期，自动转人工");
                incidents.put(incidentId, expired);
                persistSnapshot(incidentId, expired);   // 改判也是终态，落库镜像同步
                notify(incidentId, "ESCALATED");
                return expired;
            }
            // 通知 AWAITING_APPROVAL（run 返回时若状态停在闸口）或 CLOSED（重放跑完）
            notify(incidentId, "AWAITING_APPROVAL".equals(
                    String.valueOf(result.getOrDefault(IncidentState.OUTCOME, "")))
                    ? "AWAITING_APPROVAL" : "CLOSED");
            return result;
        } finally {
            diagnosisSlots.release();   // "谁拿谁还"：续跑结束（任何出口）归还配额
        }
    }

    /**
     * 判定本轮 run 是否发生了"令牌过期"型拒绝：execute 把每个动作的执行结果
     * 记入 ACTIONS 通道，工具对过期令牌返回的 REFUSED 文案带 EXPIRED 标记。
     * 独立小谓词（不用流式常量拼接）便于单测直接构造状态断言。
     */
    private static boolean tokenExpiredDuringExecute(Map<String, Object> state) {
        Object actions = state.get(IncidentState.ACTIONS);
        if (!(actions instanceof List<?> list)) {
            return false;
        }
        return list.stream().anyMatch(a -> String.valueOf(a).contains("EXPIRED"));
    }

    /** 通知出口：notify 总开关关闭时监听器为 null，直接跳过（零开销） */
    private void notify(long incidentId, String transition) {
        if (notificationListener != null) {
            notificationListener.onTransition(incidentId, transition);
        }
    }

    /**
     * 详情视图（分层读，事实源切换边界）：
     * <ol>
     *   <li>内存命中 → 内存快照 + 内存时间线（运行中/本轮进程内的事件，事实源是
     *       内存 Map——图重放语义要求完整 State 通道，DB 镜像只有摘要字段，
     *       拿 DB 视图替代会丢 evidences/plan 等过程数据）；</li>
     *   <li>内存未命中（重启后的历史事件 / 本进程从未见过的事件）→ 查 t_incident
     *       组装 DB 视图（timeline 由 t_graph_step 还原）。AWAITING_APPROVAL 的
     *       事件重启后由此恢复<b>展示</b>——但 approve 不读 DB，"重启续跑"属
     *       checkpoint resume 演进路线（类注释已钉死），本轮只保证查得到、
     *       界面渲染与历史行不再丢。</li>
     * </ol>
     * JSON 字段名/结构两条路径完全一致（前端零改动的红线）。DB 读失败降级为
     * null（404）：读路径挂了不能拖垮主链路，与写侧旁路同一条纪律。
     */
    public Map<String, Object> get(long id) {
        Map<String, Object> snapshot = incidents.get(id);
        if (snapshot != null) {
            Map<String, Object> view = new HashMap<>(snapshot);
            view.put("timeline", progressListener.timeline(id));
            return view;
        }
        // 内存未命中 → DB 历史底账（重启恢复读路径）
        com.sentryagent.persist.IncidentQueryService q = queryProvider.getIfAvailable();
        if (q != null) {
            try {
                return q.detail(id);
            } catch (Exception e) {
                log.warn("db read incident {} failed (degraded to 404): {}", id, e.getMessage());
            }
        }
        return null;
    }

    /** 原始快照（无 timeline），SSE 端点判断终态用 */
    public Map<String, Object> rawSnapshot(long id) {
        return incidents.get(id);
    }

    /**
     * 事件列表（计划书第 6 章契约 GET /api/incidents?status&page 的服务侧，分层读）。
     * 修复根因：全量测试发现该契约端点从未实现，GET /api/incidents 返回 405——
     * 前端历史表靠 localStorage、后端数据面缺位，外部消费方（看板/报表）无入口。
     *
     * <p>事实源切换边界：内存 Map 提供<b>运行中事件</b>（含本轮进程内已终态的），
     * t_incident 提供<b>重启前的历史事件</b>——两路按事件号合并去重（同号内存优先，
     * 内存是运行中真相、DB 是它落库时刻的滞后镜像），再统一按事件号升序分页。
     * status 过滤两路各自应用（DB 侧 WHERE、内存侧谓词，口径同为 outcome 精确匹配）。</p>
     *
     * <p>返回行是内存快照原样或 DB 视图（两者字段名/结构对齐），均不带 timeline
     * （列表页不关心节点明细，契约既有约定）。DB 读失败降级为纯内存列表：
     * 读路径挂了不能拖垮主链路。</p>
     */
    public List<Map<String, Object>> list(String status, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = size <= 0 ? 20 : size;
        // 内存路：运行中事件（状态过滤谓词与 DB 的 WHERE 同口径）
        Map<Long, Map<String, Object>> merged = new java.util.LinkedHashMap<>();
        incidents.values().stream()
                .filter(s -> status == null || status.isBlank()
                        || status.equals(String.valueOf(s.get(IncidentState.OUTCOME))))
                .forEach(s -> merged.put(
                        ((Number) s.getOrDefault(IncidentState.INCIDENT_ID, 0L)).longValue(), s));
        // DB 路：历史底账（同号跳过——内存优先，DB 是滞后镜像）
        com.sentryagent.persist.IncidentQueryService q = queryProvider.getIfAvailable();
        if (q != null) {
            try {
                for (Map<String, Object> row : q.listRows(status)) {
                    long id = ((Number) row.getOrDefault(IncidentState.INCIDENT_ID, 0L)).longValue();
                    merged.putIfAbsent(id, row);
                }
            } catch (Exception e) {
                log.warn("db list incidents failed (degraded to memory-only): {}", e.getMessage());
            }
        }
        return merged.values().stream()
                .sorted(Comparator.comparingLong(s ->
                        ((Number) s.getOrDefault(IncidentState.INCIDENT_ID, 0L)).longValue()))
                .skip((long) safePage * safeSize)
                .limit(safeSize)
                .toList();
    }

    /** 时间线单独暴露：SSE 补发/轮询新增的数据源 */
    public java.util.List<NodeProgressListener.TimelineEvent> timeline(long id) {
        return progressListener.timeline(id);
    }

    /**
     * 驱动一次图执行并快照终态。配额的获取/归还在调用方（createAndRun/approve
     * 的 try/finally），这里只管图执行与快照——成功/FAILED/异常三个出口都把
     * 快照写回内存仓，终态事件不再占用"诊断工位"。
     */
    private Map<String, Object> run(long id, Map<String, Object> input) {
        try {
            Optional<IncidentState> finalState = graph.invoke(input);
            Map<String, Object> data = finalState.map(IncidentState::data)
                    .orElse(new HashMap<>(input));
            incidents.put(id, data);
            log.info("incident {} finished outcome={}", id, data.get(IncidentState.OUTCOME));
            persistSnapshot(id, data);   // Phase 4：终态镜像到 t_incident（异步旁路）
            return data;
        } catch (Exception e) {
            log.error("incident {} graph execution failed", id, e);
            Map<String, Object> err = new HashMap<>(input);
            err.put(IncidentState.OUTCOME, "FAILED");
            err.put(IncidentState.REPORT, "图执行异常: " + e.getMessage());
            incidents.put(id, err);
            persistSnapshot(id, err);   // FAILED 也是终态，复盘要查得到
            return err;
        }
    }

    /** Phase 4 落库出口：persistence 关闭时 provider 无 bean，判空跳过（零开销） */
    private void persistSnapshot(long id, Map<String, Object> data) {
        com.sentryagent.persist.IncidentPersistence p = persistenceProvider.getIfAvailable();
        if (p != null) {
            p.saveIncident(id, data);
        }
    }
}
