package com.evops.rectify.dto;

/** 复勘意见：通过销项 / 不通过退回维护商重处理。 */
public class RectifyReinspectRequest {
    /** 复勘意见（不通过时必填原因） */
    private String comment;
    /** 复勘人（留空取当前登录账号） */
    private String operatorName;

    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }
    public String getOperatorName() { return operatorName; }
    public void setOperatorName(String operatorName) { this.operatorName = operatorName; }
}
