package com.evops.install.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.time.LocalDate;

/** 安装队资质登记（一张证一行）。 */
@Data
public class CertRegisterRequest {
    /** 安装队编号 */
    @NotBlank(message = "安装队编号不能为空")
    private String teamNo;
    /** 资质类型：WORK_AT_HEIGHT 高空作业证 / ELECTRICIAN 电工证 / SAFETY_OTHER 其他 */
    @NotBlank(message = "资质类型不能为空")
    private String certType;
    /** 证书编号，全局唯一 */
    @NotBlank(message = "证书编号不能为空")
    private String certNo;
    /** 证书名称（持证人等） */
    private String certName;
    private LocalDate issueDate;
    /** 到期日（当日仍有效，次日起过期） */
    @NotNull(message = "资质到期日不能为空")
    private LocalDate expiryDate;
    /** 经手人，留空取当前登录账号 */
    private String operatorName;
    private String remark;
}
