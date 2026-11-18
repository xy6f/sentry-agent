package com.sentryagent.persist;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 诊断事件实体（t_incident）——IncidentService 内存快照的落库对应物（计划书第 5 章） */
@Data
@TableName("t_incident")
public class IncidentEntity {

    /** 与内存 idGen 同源；落库时回写而非自增（保证重启前后 ID 连续） */
    @TableId(type = IdType.INPUT)
    private Long id;

    private String service;

    private String alertSummary;

    private String outcome;

    private String rootCause;

    private Integer totalTokens;

    private LocalDateTime startedAt;

    private LocalDateTime closedAt;
}
