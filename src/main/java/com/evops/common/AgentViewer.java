package com.evops.common;

import org.apache.shiro.SecurityUtils;
import org.apache.shiro.subject.Subject;

/**
 * F9 代理分账的数据隔离视角：代理只能查自己的账单，别家的看不见。
 *
 * - 财务 / 系统（system、finance 角色）：allAgents 看全部，可挂协议/调比例/终止/出结算；
 * - 代理（agent 角色，按绑定登录账号）：只看 agent.loginUsername == username 的代理自己的账单；
 * - 其他登录账号：按受限视角处理，名下没有代理档案，查不到任何分账数据。
 *
 * 与 {@link Viewer} 分开：授信按“归属销售”隔离，代理分账按“代理绑定登录账号”隔离，边界互不混用。
 */
public class AgentViewer {

    private final String username;
    /** 财务/系统视角：不受代理归属限制，可管理协议与结算 */
    private final boolean allAgents;

    private AgentViewer(String username, boolean allAgents) {
        this.username = username;
        this.allAgents = allAgents;
    }

    public static AgentViewer all(String username) {
        return new AgentViewer(username, true);
    }

    public static AgentViewer agent(String username) {
        return new AgentViewer(username, false);
    }

    /** 从当前 Shiro 登录主体构造视角；未登录返回 null。 */
    public static AgentViewer current() {
        try {
            Subject subject = SecurityUtils.getSubject();
            if (subject == null || !subject.isAuthenticated() || subject.getPrincipal() == null) {
                return null;
            }
            String name = subject.getPrincipal().toString();
            if (subject.hasRole("system") || subject.hasRole("finance")) {
                return all(name);
            }
            // 代理及其余账号一律按受限视角：只能看绑定到自己登录账号的代理账单
            return agent(name);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 读账单前的归属校验：代理视角读别家代理即拒。 */
    public void checkOwn(String boundLoginUsername) {
        if (!allAgents && !username.equals(boundLoginUsername)) {
            throw new BusinessException("只能查看本代理名下账单，别家代理的分账数据无权查看");
        }
    }

    /** 协议管理 / 出结算只许财务/系统。 */
    public void requireManage() {
        if (!allAgents) {
            throw new BusinessException("只有财务/系统可以挂协议、调整比例或出结算，代理账号仅可查询自己的账单");
        }
    }

    public String getUsername() { return username; }
    public boolean isAllAgents() { return allAgents; }
    /** 是否代理（受限）视角 */
    public boolean isScoped() { return !allAgents; }
}
