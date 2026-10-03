package com.evops.config;

import com.evops.common.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.shiro.realm.Realm;
import org.apache.shiro.realm.SimpleAccountRealm;
import org.apache.shiro.spring.web.config.DefaultShiroFilterChainDefinition;
import org.apache.shiro.spring.web.config.ShiroFilterChainDefinition;
import org.apache.shiro.web.filter.authc.FormAuthenticationFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletResponse;

@Configuration
public class ShiroConfig {

    @Bean
    public Realm localRealm() {
        SimpleAccountRealm realm = new SimpleAccountRealm();
        realm.addAccount("bootstrap", "bootstrap", "system");
        // 周例会演示账号
        realm.addAccount("demo", "demo", "system");
        // F5：财务（可审批额度、看全部客户）
        realm.addAccount("finance1", "finance1", "finance");
        // F5：销售（只能看/操作自己名下客户，只能提交额度申请）
        realm.addAccount("sales1", "sales1", "sales");
        realm.addAccount("sales2", "sales2", "sales");
        // F6：巡查员（现场上报异常）、维护商（处理回传）；调度派单与复勘用 demo/system 账号
        realm.addAccount("inspector1", "inspector1", "inspector");
        realm.addAccount("vendor1", "vendor1", "vendor");
        // F8：安装师傅（到场拍照打卡、完工回传）；调度匹配/改期用 demo/system 账号
        realm.addAccount("installer1", "installer1", "installer");
        // F9：代理（只能查自己名下账单，不能挂协议/调比例/出结算）；代理协议与结算用 demo/system 或 finance1
        realm.addAccount("agent1", "agent1", "agent");
        realm.addAccount("agent2", "agent2", "agent");
        // F10：合规岗（维护受限行业类目名单、批量冻结在途单、解冻/强制落架处置）
        realm.addAccount("compliance1", "compliance1", "compliance");
        return realm;
    }

    /**
     * 未登录访问受保护接口时回 JSON 401，而不是重定向登录页。
     * Bean 名必须为 authc 才能被 Shiro 按链定义中的 "authc" 名字取用、覆盖默认表单过滤器。
     */
    @Bean(name = "authc")
    public FormAuthenticationFilter authcFilter(ObjectMapper objectMapper) {
        return new FormAuthenticationFilter() {
            @Override
            protected boolean onAccessDenied(ServletRequest request, ServletResponse response) throws Exception {
                HttpServletResponse resp = (HttpServletResponse) response;
                resp.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                resp.setContentType("application/json;charset=UTF-8");
                resp.getWriter().write(objectMapper.writeValueAsString(ApiResponse.fail("未登录或登录已失效")));
                return false;
            }
        };
    }

    /**
     * 阻止 Spring Boot 把上面的 authc Filter Bean 再注册成“全局 Servlet 过滤器”。
     * 该过滤器只应作为 Shiro 过滤器链中的命名过滤器使用；
     * 若被 Boot 全局注册，会绕过 anon 链，把登录页/健康检查也一并拦掉。
     */
    @Bean
    public FilterRegistrationBean<FormAuthenticationFilter> authcFilterRegistration(
            FormAuthenticationFilter authcFilter) {
        FilterRegistrationBean<FormAuthenticationFilter> registration = new FilterRegistrationBean<>(authcFilter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public ShiroFilterChainDefinition shiroFilterChainDefinition() {
        DefaultShiroFilterChainDefinition chain = new DefaultShiroFilterChainDefinition();
        chain.addPathDefinition("/api/health", "anon");
        chain.addPathDefinition("/api/login", "anon");
        chain.addPathDefinition("/", "anon");
        chain.addPathDefinition("/index.html", "anon");
        chain.addPathDefinition("/favicon.ico", "anon");
        chain.addPathDefinition("/error", "anon");
        chain.addPathDefinition("/**", "authc");
        return chain;
    }
}
