package com.evops.install.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

/**
 * 安装队单张资质（一张证一行）。高空作业证 WORK_AT_HEIGHT 是高空点位硬门槛。
 * certNo 全局唯一；状态 VALID 在册有效 / EXPIRED 已过期 / REVOKED 已吊销。
 * 是否在有效期以 expiryDate 实时比较：到期当日仍有效，次日起过期。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_install_cert")
public class InstallCert extends BaseEntity {
    private Long teamId;
    /** WORK_AT_HEIGHT / ELECTRICIAN / SAFETY_OTHER */
    private String certType;
    /** 证书编号，全局唯一 */
    private String certNo;
    private String certName;
    private LocalDate issueDate;
    /** 到期日（当日仍有效） */
    private LocalDate expiryDate;
    /** VALID / EXPIRED / REVOKED */
    private String status;
    private String revokeReason;
    /** 登记经手人 */
    private String operatorName;
    private String remark;
}
