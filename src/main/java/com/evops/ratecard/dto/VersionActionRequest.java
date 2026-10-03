package com.evops.ratecard.dto;

import lombok.Data;

/** 版本动作（发布 / 作废）、年度封存的通用请求。 */
@Data
public class VersionActionRequest {
    /** 经手人，留空取当前登录账号 */
    private String operatorName;
    /** 动作原因（作废时建议填写） */
    private String reason;
}
