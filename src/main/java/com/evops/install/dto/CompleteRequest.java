package com.evops.install.dto;

import lombok.Data;

import java.time.LocalDateTime;

/** 完工回传回执（完工照片可选，回执内容必填）。 */
@Data
public class CompleteRequest {
    /** 完工回执内容（安装结果/件料），必填 */
    private String finishResult;
    /** 完工时刻，留空取当前时刻 */
    private LocalDateTime finishTime;
    /** 经手人，留空取当前登录账号 */
    private String operatorName;
}
