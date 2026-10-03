package com.evops.ledger.support;

import com.evops.ledger.entity.PostingOrder;

/**
 * 上刊单与客户授信域之间的端口（接口定义在底账侧，由 credit 域提供实现）。
 *
 * 这样底账域不编译依赖授信域，避免循环依赖；授信模块未启用（无实现）时，
 * {@code OrderService} 经 ObjectProvider 取不到实现，上刊流程与 F1 完全一致。
 *
 * 口径：引用报价单的上刊单为授信单，提交时占用客户额度，撤刊/对冲时释放。
 */
public interface PostingCreditGateway {

    /**
     * 授信上刊提交口：按报价单快照占用客户额度。
     * 余额不足 / 客户冻结或不存在 / 报价不可用 / 非名下客户时抛 BusinessException，
     * 调用方事务整体回滚（单据与档期占用都不落）。
     *
     * @param order      已落库、已占档期的上刊单（含 id、点位、归属期间）
     * @param quoteNo    引用的已提交报价单号（客户与含税占用额从其快照取）
     * @param operatorName 经手人（留空取当前登录账号），用于归属校验与留痕
     */
    void occupyFor(PostingOrder order, String quoteNo, String operatorName);

    /**
     * 撤刊/对冲释放额度。
     *
     * @param order    被撤刊/对冲的原授信单
     * @param reversed true=对冲更正（释放额度但不转应收）；false=落架撤刊（未核销额转应收）
     */
    void releaseFor(PostingOrder order, boolean reversed, String operatorName);
}
