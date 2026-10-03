package com.evops.install.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 安装队：一个队一行。状态 ACTIVE 在册可派 / DISABLED 停用不参与匹配。
 * 一个队同一时段只排一个点位，口径落在 t_install_order 排期重叠校验。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_install_team")
public class InstallTeam extends BaseEntity {
    /** 队编号 IT+序列风格，全局唯一 */
    private String teamNo;
    /** 队名，全局唯一 */
    private String teamName;
    /** 队长/负责人 */
    private String leaderName;
    /** 联系方式 */
    private String contact;
    /** ACTIVE / DISABLED */
    private String status;
    /** 资质档案当前版本号（每登记/变更一次资质 +1） */
    private Integer currentVersionNo;
    /** 建档经手人 */
    private String operatorName;
    private String remark;
}
