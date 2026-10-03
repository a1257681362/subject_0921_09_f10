package com.evops.ledger.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.ledger.entity.OrderEvent;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface OrderEventMapper extends BaseMapper<OrderEvent> {
}
