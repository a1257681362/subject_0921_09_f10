package com.evops.agent.dto;

import com.evops.agent.entity.AgentSettlement;
import com.evops.agent.entity.AgentSettlementLine;
import lombok.Data;

import java.util.List;

/** 结算单详情：结算头 + 逐笔流水分账行。 */
@Data
public class SettlementDetailView {
    private AgentSettlement settlement;
    private List<AgentSettlementLine> lines;
}
