package com.sentryagent.persist;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 事件主表 Mapper（BaseMapper 即够：单表 insert/updateById/selectById/selectList） */
@Mapper
public interface IncidentMapper extends BaseMapper<IncidentEntity> {
}
