package com.evops.ledger.support;

import com.evops.common.BusinessException;
import org.apache.shiro.SecurityUtils;
import org.apache.shiro.subject.Subject;

/** 经手人口径：请求显式指定优先，否则取当前登录账号。 */
public final class Operators {

    private Operators() {}

    public static String resolve(String explicit) {
        if (explicit != null && !explicit.trim().isEmpty()) {
            return explicit.trim();
        }
        try {
            Subject subject = SecurityUtils.getSubject();
            if (subject.isAuthenticated() && subject.getPrincipal() != null) {
                return subject.getPrincipal().toString();
            }
        } catch (Throwable ignored) {
            // 非 Web 线程 / 无安全管理器上下文时按“未登录”处理，要求显式经手人
        }
        throw new BusinessException("未识别经手人：请先登录或在单据上填写经手人");
    }
}
