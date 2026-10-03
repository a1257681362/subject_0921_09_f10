package com.evops.install.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/** 资质吊销。 */
@Data
public class CertRevokeRequest {
    /** 吊销原因 */
    @NotBlank(message = "吊销原因不能为空")
    private String reason;
    /** 经手人，留空取当前登录账号 */
    private String operatorName;
}
