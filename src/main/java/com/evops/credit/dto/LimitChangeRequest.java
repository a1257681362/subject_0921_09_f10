package com.evops.credit.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import java.math.BigDecimal;

/**
 * 额度变更申请：ADJUST 调额（给 afterLimit）/ FREEZE 冻结 / UNFREEZE 解冻。
 * 一律先进 PENDING，财务审批后才生效；前后值落在额度版本里。
 */
@Data
public class LimitChangeRequest {
    /** ADJUST / FREEZE / UNFREEZE */
    @NotBlank(message = "变更类型不能为空")
    private String changeType;
    /** 调额后的目标额度；ADJUST 必填，FREEZE/UNFREEZE 忽略 */
    private BigDecimal afterLimit;
    /** 申请原因 */
    private String reason;
    /** 申请人，留空取当前登录账号 */
    private String operatorName;
}
