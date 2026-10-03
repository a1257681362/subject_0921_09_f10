package com.evops.install.dto;

import lombok.Data;

import java.time.LocalDateTime;

/** 师傅到场打卡（照片必传，在控制器 multipart 强制）。 */
@Data
public class ArriveRequest {
    /** 到场时刻，留空取当前时刻 */
    private LocalDateTime arriveTime;
    /** 打卡师傅/经手人，留空取当前登录账号 */
    private String operatorName;
}
