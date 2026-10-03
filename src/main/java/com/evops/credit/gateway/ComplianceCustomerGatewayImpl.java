package com.evops.credit.gateway;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.compliance.support.ComplianceCustomerGateway;
import com.evops.credit.entity.Customer;
import com.evops.credit.mapper.CustomerMapper;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/** 合规侧"按行业类目查建档客户"端口的授信实现：批量冻结预览与记录留痕用。 */
@Service
public class ComplianceCustomerGatewayImpl implements ComplianceCustomerGateway {

    private final CustomerMapper customerMapper;

    public ComplianceCustomerGatewayImpl(CustomerMapper customerMapper) {
        this.customerMapper = customerMapper;
    }

    @Override
    public List<CustomerBrief> findByCategories(Collection<String> categories) {
        if (categories == null || categories.isEmpty()) {
            return Collections.emptyList();
        }
        List<Customer> customers = customerMapper.selectList(new LambdaQueryWrapper<Customer>()
                .in(Customer::getIndustryCategory, categories));
        return customers.stream()
                .map(c -> new CustomerBrief(c.getId(), c.getCustomerNo(), c.getCustomerName(), c.getIndustryCategory()))
                .collect(Collectors.toList());
    }
}
