package com.evops.credit.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.common.BusinessException;
import com.evops.common.Viewer;
import com.evops.credit.CreditEnums;
import com.evops.credit.dto.CustomerCreateRequest;
import com.evops.credit.entity.CreditLimitVersion;
import com.evops.credit.entity.Customer;
import com.evops.credit.mapper.CreditLimitVersionMapper;
import com.evops.credit.mapper.CustomerMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

/**
 * 客户档案域：建档即授信（v1 直接生效，前值 0）；客户名与报价单客户名精确对应、全局唯一；
 * 归属销售决定数据隔离边界。额度/状态的后续变更走 {@link CreditLimitService} 审批。
 */
@Service
public class CustomerService {

    static final int MONEY_SCALE = 2;

    private final CustomerMapper customerMapper;
    private final CreditLimitVersionMapper versionMapper;

    public CustomerService(CustomerMapper customerMapper,
                           CreditLimitVersionMapper versionMapper) {
        this.customerMapper = customerMapper;
        this.versionMapper = versionMapper;
    }

    /** 客户建档：给信用额度，自动记 v1（INITIAL，直接生效，前值 0）。 */
    @Transactional(rollbackFor = Exception.class)
    public Customer create(CustomerCreateRequest request, Viewer viewer) {
        String name = request.getCustomerName().trim();
        // 销售只能把客户建在自己名下；财务/系统可指定归属销售，留空归经手人
        String owner = viewer.isAllCustomers()
                ? (request.getOwnerSales() != null && !request.getOwnerSales().trim().isEmpty()
                    ? request.getOwnerSales().trim() : viewer.getUsername())
                : viewer.getUsername();
        BigDecimal limit = normalizeMoney(request.getCreditLimit());

        Long dup = customerMapper.selectCount(
                new LambdaQueryWrapper<Customer>().eq(Customer::getCustomerName, name));
        if (dup != null && dup > 0) {
            throw new BusinessException("客户「" + name + "」已建档，客户名全局唯一");
        }

        Customer customer = new Customer();
        customer.setCustomerNo(generateCustomerNo());
        customer.setCustomerName(name);
        customer.setOwnerSales(owner);
        customer.setCreditLimit(limit);
        customer.setOccupiedAmount(BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP));
        customer.setCreditStatus(CreditEnums.CUSTOMER_ACTIVE);
        customer.setContactName(request.getContactName());
        customer.setOperatorName(viewer.getUsername());
        customer.setRemark(request.getRemark());
        try {
            customerMapper.insert(customer);
        } catch (DuplicateKeyException e) {
            throw new BusinessException("客户「" + name + "」已建档，客户名全局唯一");
        }

        // 建档授信即 v1 直接生效（前值 0、前状态空），建档经手人即生效人
        CreditLimitVersion v1 = new CreditLimitVersion();
        v1.setCustomerId(customer.getId());
        v1.setVersionNo(CreditEnums.FIRST_VERSION_NO);
        v1.setChangeType(CreditEnums.CHANGE_INITIAL);
        v1.setBeforeLimit(BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP));
        v1.setAfterLimit(limit);
        v1.setBeforeStatus(null);
        v1.setAfterStatus(CreditEnums.CUSTOMER_ACTIVE);
        v1.setStatus(CreditEnums.VERSION_EFFECTIVE);
        v1.setApplicantName(viewer.getUsername());
        v1.setApplyTime(LocalDateTime.now());
        v1.setApproverName(viewer.getUsername());
        v1.setApproveComment("建档授信");
        v1.setApproveTime(LocalDateTime.now());
        versionMapper.insert(v1);

        Customer update = new Customer();
        update.setId(customer.getId());
        update.setCurrentVersionId(v1.getId());
        update.setCurrentVersionNo(CreditEnums.FIRST_VERSION_NO);
        customerMapper.updateById(update);
        customer.setCurrentVersionId(v1.getId());
        customer.setCurrentVersionNo(CreditEnums.FIRST_VERSION_NO);
        return customer;
    }

    /** 翻查客户：销售只看自己名下，财务/系统看全部；可按客户名模糊、状态过滤。 */
    public java.util.List<Customer> list(Viewer viewer, String keyword, String status) {
        LambdaQueryWrapper<Customer> wrapper = new LambdaQueryWrapper<>();
        if (viewer.isScoped()) {
            wrapper.eq(Customer::getOwnerSales, viewer.getUsername());
        }
        if (keyword != null && !keyword.trim().isEmpty()) {
            wrapper.like(Customer::getCustomerName, keyword.trim());
        }
        if (status != null && !status.trim().isEmpty()) {
            wrapper.eq(Customer::getCreditStatus, status.trim());
        }
        return customerMapper.selectList(wrapper.orderByDesc(Customer::getId));
    }

    public Customer requireById(Long id) {
        Customer customer = customerMapper.selectById(id);
        if (customer == null) {
            throw new BusinessException("客户不存在: id=" + id);
        }
        return customer;
    }

    public Customer requireByNo(String customerNo) {
        Customer customer = customerMapper.selectOne(new LambdaQueryWrapper<Customer>()
                .eq(Customer::getCustomerNo, customerNo == null ? "" : customerNo.trim()));
        if (customer == null) {
            throw new BusinessException("客户不存在: " + customerNo);
        }
        return customer;
    }

    /** 按客户名精确取客户（报价单认领用），取不到返回 null。 */
    public Customer findByName(String customerName) {
        if (customerName == null || customerName.trim().isEmpty()) {
            return null;
        }
        return customerMapper.selectOne(new LambdaQueryWrapper<Customer>()
                .eq(Customer::getCustomerName, customerName.trim()));
    }

    /** 读取并做归属校验。 */
    public Customer requireViewable(String customerNo, Viewer viewer) {
        Customer customer = requireByNo(customerNo);
        viewer.checkOwn(customer.getOwnerSales());
        return customer;
    }

    /** 操作前归属校验（变更/回款等写动作）。 */
    public Customer requireOwned(Long customerId, Viewer viewer) {
        Customer customer = requireById(customerId);
        viewer.checkOwn(customer.getOwnerSales());
        return customer;
    }

    public static BigDecimal normalizeMoney(BigDecimal value) {
        if (value == null || value.signum() < 0) {
            throw new BusinessException("金额不能为空且不能为负");
        }
        return value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private String generateCustomerNo() {
        long seq = customerMapper.nextCustomerSeq();
        return String.format("CU%010d", seq);
    }
}
