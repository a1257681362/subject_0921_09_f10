package com.evops.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.agent.entity.AgentAgreement;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AgentAgreementMapper extends BaseMapper<AgentAgreement> {
    /** 协议号全局序列。 */
    @Select("SELECT NEXT VALUE FOR seq_agent_agreement_no")
    long nextAgreementSeq();
}
