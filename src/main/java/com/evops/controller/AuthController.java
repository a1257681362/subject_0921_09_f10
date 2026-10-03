package com.evops.controller;

import com.evops.common.ApiResponse;
import org.apache.shiro.SecurityUtils;
import org.apache.shiro.authc.UsernamePasswordToken;
import org.apache.shiro.subject.Subject;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import java.util.Collections;

/** 登录/登出/当前操作人：单据上的“谁经手”默认取当前登录账号。 */
@RestController
@RequestMapping("/api")
public class AuthController {

    public static class LoginRequest {
        @NotBlank(message = "用户名不能为空")
        private String username;
        @NotBlank(message = "密码不能为空")
        private String password;

        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
    }

    @PostMapping("/login")
    public ApiResponse<Object> login(@Valid @RequestBody LoginRequest request) {
        Subject subject = SecurityUtils.getSubject();
        if (!subject.isAuthenticated()) {
            subject.login(new UsernamePasswordToken(request.getUsername(), request.getPassword()));
        }
        return ApiResponse.ok(Collections.singletonMap("username", subject.getPrincipal()));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout() {
        SecurityUtils.getSubject().logout();
        return ApiResponse.ok(null);
    }

    @GetMapping("/me")
    public ApiResponse<Object> me(HttpServletRequest request) {
        Subject subject = SecurityUtils.getSubject();
        return ApiResponse.ok(Collections.singletonMap("username", subject.getPrincipal()));
    }
}
