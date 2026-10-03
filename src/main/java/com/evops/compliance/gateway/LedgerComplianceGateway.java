package com.evops.compliance.gateway;

import com.evops.compliance.service.CategoryListService;
import com.evops.ledger.support.PostingComplianceGateway;
import org.springframework.stereotype.Service;

/** 底账侧名单校验端口的合规实现：普通上刊单提交时按填报类目校验生效名单。 */
@Service
public class LedgerComplianceGateway implements PostingComplianceGateway {

    private final CategoryListService categoryListService;

    public LedgerComplianceGateway(CategoryListService categoryListService) {
        this.categoryListService = categoryListService;
    }

    @Override
    public void requireCategoryAllowed(String industryCategory) {
        categoryListService.requireNotRestricted(industryCategory, "上刊提交");
    }
}
