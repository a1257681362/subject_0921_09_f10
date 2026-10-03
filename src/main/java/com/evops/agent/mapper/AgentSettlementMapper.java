package com.evops.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.agent.entity.AgentSettlement;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AgentSettlementMapper extends BaseMapper<AgentSettlement> {
    /** 结算单号全局序列。 */
    @Select("SELECT NEXT VALUE FOR seq_agent_settlement_no")
    long nextSettlementSeq();
}
