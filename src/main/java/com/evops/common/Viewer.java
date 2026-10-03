package com.evops.common;

import org.apache.shiro.SecurityUtils;
import org.apache.shiro.subject.Subject;

/**
 * 数据隔离视角：客户授信“销售只能看自己名下客户”的统一判定。
 *
 * - 财务 / 系统（system、finance 角色）：allCustomers 看全部，canApprove 可审批额度；
 * - 销售（sales 角色或按账号）：只看/操作 ownerSales == username 的客户。
 *
 * Web 请求里由 {@link #current()} 从 Shiro 主体构造；没有登录上下文的批处理/测试可显式
 * {@link #sales(String)} / {@link #finance(String)} 构造，便于在无 Shiro 的集成测试里验证隔离。
 */
public class Viewer {

    private final String username;
    /** 财务/系统视角：不受归属限制 */
    private final boolean allCustomers;
    /** 可审批额度变更（职责分离：销售只能申请） */
    private final boolean canApprove;
    /** 可维护受限行业类目名单与处置合规冻结（合规岗/系统） */
    private final boolean listAdmin;

    private Viewer(String username, boolean allCustomers, boolean canApprove, boolean listAdmin) {
        this.username = username;
        this.allCustomers = allCustomers;
        this.canApprove = canApprove;
        this.listAdmin = listAdmin;
    }

    public static Viewer sales(String username) {
        return new Viewer(username, false, false, false);
    }

    public static Viewer finance(String username) {
        return new Viewer(username, true, true, false);
    }

    public static Viewer system(String username) {
        return new Viewer(username, true, true, true);
    }

    /** 合规岗：跨销售看全部客户（批量冻结要命中全部在途单），但不能审批额度 */
    public static Viewer compliance(String username) {
        return new Viewer(username, true, false, true);
    }

    /** 从当前 Shiro 登录主体构造视角；未登录返回 null（调用方决定如何兜底）。 */
    public static Viewer current() {
        try {
            Subject subject = SecurityUtils.getSubject();
            if (subject == null || !subject.isAuthenticated() || subject.getPrincipal() == null) {
                return null;
            }
            String name = subject.getPrincipal().toString();
            if (subject.hasRole("system")) {
                return system(name);
            }
            if (subject.hasRole("finance")) {
                return finance(name);
            }
            if (subject.hasRole("compliance")) {
                return compliance(name);
            }
            // 其余登录账号一律按销售视角（只看自己名下）
            return sales(name);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 经手动作视角：优先取登录主体；没有登录上下文时（如无 Shiro 的集成测试/异步线程），
     * 用显式经手人构造销售视角兜底，财务动作不会走这条路径（财务直接带 finance 视角调服务）。
     */
    public static Viewer forOperator(String explicitOperator) {
        Viewer current = current();
        if (current != null) {
            return current;
        }
        if (explicitOperator == null || explicitOperator.trim().isEmpty()) {
            throw new BusinessException("未识别经手人：请先登录或在单据上填写经手人");
        }
        return sales(explicitOperator.trim());
    }

    /** 归属校验：销售视角操作非名下客户即拒。 */
    public void checkOwn(String ownerSales) {
        if (!allCustomers && !username.equals(ownerSales)) {
            throw new BusinessException("客户不在你名下，无权操作（归属销售：" + ownerSales + "）");
        }
    }

    public void requireApprove() {
        if (!canApprove) {
            throw new BusinessException("只有财务/系统可以审批信用额度变更，销售只能提交申请");
        }
    }

    /** 名单维护与冻结处置只认合规岗/系统。 */
    public void requireListAdmin() {
        if (!listAdmin) {
            throw new BusinessException("只有合规岗/系统可以维护受限行业名单与处置合规冻结");
        }
    }

    public String getUsername() { return username; }
    public boolean isAllCustomers() { return allCustomers; }
    public boolean isCanApprove() { return canApprove; }
    /** 是否合规岗/系统（可维护名单、处置冻结） */
    public boolean isListAdmin() { return listAdmin; }
    /** 是否销售（受限）视角 */
    public boolean isScoped() { return !allCustomers; }
}
