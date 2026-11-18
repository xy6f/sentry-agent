-- sentry-agent 事件持久化建表脚本（Phase 4，表结构对齐计划书第 5 章）。
-- 库需先建：CREATE DATABASE IF NOT EXISTS sentry_agent DEFAULT CHARSET utf8mb4;
-- 幂等设计：全部 IF NOT EXISTS，配合 spring sql.init.mode=always 启动安全执行。

-- 事件主表：IncidentService 内存 Map 的落库对应物
CREATE TABLE IF NOT EXISTS t_incident (
  id            BIGINT PRIMARY KEY,               -- 与内存 idGen 同源，落库时回写
  service       VARCHAR(64)  NOT NULL,
  alert_summary VARCHAR(512) NOT NULL,
  outcome       VARCHAR(32)  NOT NULL COMMENT 'RUNNING/AWAITING_APPROVAL/CLOSED/ESCALATED/FAILED',
  root_cause    VARCHAR(1024) NULL,
  total_tokens  INT          NOT NULL DEFAULT 0,
  started_at    DATETIME     NOT NULL,
  closed_at     DATETIME     NULL,
  KEY idx_outcome (outcome),
  KEY idx_started (started_at)
) ENGINE=InnoDB COMMENT='诊断事件主表';

-- 图步骤审计表：NodeProgressListener 每条时间线的落库（可"时间旅行"回放的数据源）
CREATE TABLE IF NOT EXISTS t_graph_step (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  incident_id BIGINT      NOT NULL,
  node_name   VARCHAR(32) NOT NULL,
  digest      VARCHAR(512) NULL,
  at          DATETIME    NOT NULL,
  KEY idx_incident (incident_id)
) ENGINE=InnoDB COMMENT='图节点审计时间线';

-- 证据表
CREATE TABLE IF NOT EXISTS t_evidence (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  incident_id BIGINT      NOT NULL,
  source      VARCHAR(32) NOT NULL COMMENT 'logs/metrics/containers/deploys',
  content     TEXT        NOT NULL,
  collected_at DATETIME   NOT NULL,
  KEY idx_incident (incident_id)
) ENGINE=InnoDB COMMENT='诊断证据';

-- 审批表
CREATE TABLE IF NOT EXISTS t_approval (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  incident_id BIGINT      NOT NULL,
  plan_json   VARCHAR(2048) NULL COMMENT '批准时的处置计划快照',
  risk_level  VARCHAR(8)  NOT NULL,
  status      VARCHAR(16) NOT NULL COMMENT 'PENDING/APPROVED/REJECTED/EXPIRED',
  decided_at  DATETIME    NULL,
  expire_at   DATETIME    NULL COMMENT '一次性令牌过期时刻（语义对齐计划书）',
  KEY idx_incident (incident_id)
) ENGINE=InnoDB COMMENT='人工审批记录';

-- 动作执行日志表
CREATE TABLE IF NOT EXISTS t_action_exec_log (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  incident_id BIGINT      NOT NULL,
  approval_id BIGINT      NULL,
  action_type VARCHAR(32) NOT NULL COMMENT 'RESTART/SCALE/ROLLBACK',
  target      VARCHAR(128) NOT NULL,
  result      VARCHAR(1024) NULL,
  executed_at DATETIME    NOT NULL,
  KEY idx_incident (incident_id)
) ENGINE=InnoDB COMMENT='自愈动作执行日志';
