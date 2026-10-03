package com.evops.ledger.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.ledger.entity.PostingOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface PostingOrderMapper extends BaseMapper<PostingOrder> {

    /** 取全局唯一单号序列下一个值，并发取号不重。 */
    @Select("SELECT NEXT VALUE FOR seq_order_no")
    long nextOrderSeq();
}
