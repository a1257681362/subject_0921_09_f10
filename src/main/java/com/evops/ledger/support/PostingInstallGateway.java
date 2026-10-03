package com.evops.ledger.support;

import com.evops.ledger.entity.PostingOrder;

/**
 * 上刊单与安装派单域之间的端口（接口定义在底账侧，由 install 域提供实现）。
 *
 * 底账域不编译依赖安装域，避免循环依赖；安装模块未启用（无实现）时，{@code OrderService}
 * 经 ObjectProvider 取不到实现，上刊流程与 F1 完全一致。
 *
 * 口径：上刊单确认（上刊）后自动生成安装派单，按资质与档期匹配安装队；
 * 上刊单对冲更正时联动取消未完工的派单。安装派单不阻断上刊——
 * 没有合格空闲队时生成「待匹配」派单，由补扫或人工补匹配继续。
 */
public interface PostingInstallGateway {

    /**
     * 上刊单确认后：自动生成一张安装派单并立即尝试按资质+档期匹配。
     * 无合格空闲队也正常返回（派单落「待匹配」），不抛异常、不回滚上刊。
     *
     * @param order        已落库、已占档期的上刊单（含 id、点位、档期周）
     * @param operatorName 经手人（留空取当前登录账号）
     */
    void onPostingConfirmed(PostingOrder order, String operatorName);

    /**
     * 上刊单对冲更正后：联动取消其未完工的派单并释放安装队档期；
     * 已完工的派单保留（安装已实际发生），只留事件不报错、不阻断对冲。
     *
     * @param original     被对冲的原上刊单
     * @param operatorName 经手人
     */
    void onPostingOffset(PostingOrder original, String operatorName);
}
