package com.evops.ledger.support;

/**
 * 上刊单与受限行业类目名单域之间的端口（接口定义在底账侧，由 compliance 域提供实现）。
 *
 * 这样底账域不编译依赖合规模块，避免循环依赖；合规模块未启用（无实现）时，
 * {@code OrderService} 经 ObjectProvider 取不到实现，上刊流程与 F1 完全一致。
 *
 * 口径：普通上刊单提交时按填报的行业类目校验生效名单，命中当场拒收（提示带依据）。
 */
public interface PostingComplianceGateway {

    /**
     * 上刊提交口：行业类目命中当前生效受限名单即抛 BusinessException（提示含类目、依据、名单版本）；
     * 类目为空（历史数据）或名单未发布任何版本时放行。
     *
     * @param industryCategory 普通上刊单填报的行业类目
     */
    void requireCategoryAllowed(String industryCategory);
}
