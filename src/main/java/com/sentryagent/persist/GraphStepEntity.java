package com.sentryagent.persist;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 图节点审计时间线（t_graph_step）——NodeProgressListener 时间线的落库对应物 */
@Data
@TableName("t_graph_step")
public class GraphStepEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long incidentId;

    private String nodeName;

    private String digest;

    private LocalDateTime at;
}
