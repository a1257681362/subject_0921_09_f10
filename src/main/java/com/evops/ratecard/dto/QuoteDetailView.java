package com.evops.ratecard.dto;

import com.evops.ratecard.entity.Quote;
import com.evops.ratecard.entity.QuoteLine;
import lombok.Data;

import java.util.List;

/** 报价详情：报价单头 + 快照行。提交后只读，没有任何编辑接口。 */
@Data
public class QuoteDetailView {
    private Quote quote;
    private List<QuoteLine> lines;
}
