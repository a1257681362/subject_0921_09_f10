package com.evops.ledger.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.ledger.entity.OrderReceipt;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface OrderReceiptMapper extends BaseMapper<OrderReceipt> {
}
