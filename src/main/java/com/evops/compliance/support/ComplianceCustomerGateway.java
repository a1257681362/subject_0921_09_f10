package com.evops.compliance.support;

import lombok.Data;

import java.util.Collection;
import java.util.List;

/**
 * 合规模块与客户授信域之间的端口（接口定义在合规侧，由 credit 域提供实现）。
 *
 * 这样合规模块不编译依赖授信域，避免循环依赖（credit 已依赖 compliance 做名单校验）；
 * 无实现时批量冻结照常按单上类目快照冻结，只是记录行缺客户编号/名称快照。
 */
public interface ComplianceCustomerGateway {

    /** 按行业类目查建档客户（批量冻结预览与记录留痕用）。 */
    List<CustomerBrief> findByCategories(Collection<String> categories);

    /** 客户简要（冻结记录快照用）。 */
    @Data
    class CustomerBrief {
        private Long customerId;
        private String customerNo;
        private String customerName;
        private String industryCategory;

        public CustomerBrief(Long customerId, String customerNo, String customerName, String industryCategory) {
            this.customerId = customerId;
            this.customerNo = customerNo;
            this.customerName = customerName;
            this.industryCategory = industryCategory;
        }
    }
}
