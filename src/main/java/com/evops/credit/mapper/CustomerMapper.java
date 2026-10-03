package com.evops.credit.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.credit.entity.Customer;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface CustomerMapper extends BaseMapper<Customer> {
    /** 客户编号全局序列，并发取号不重。 */
    @Select("SELECT NEXT VALUE FOR seq_customer_no")
    long nextCustomerSeq();
}
