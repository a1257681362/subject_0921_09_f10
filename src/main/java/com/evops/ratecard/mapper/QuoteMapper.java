package com.evops.ratecard.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.ratecard.entity.Quote;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface QuoteMapper extends BaseMapper<Quote> {

    /** 取全局唯一报价单号序列下一个值，并发取号不重。 */
    @Select("SELECT NEXT VALUE FOR seq_quote_no")
    long nextQuoteSeq();
}
