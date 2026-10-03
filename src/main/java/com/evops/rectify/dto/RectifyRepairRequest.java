package com.evops.rectify.dto;

/** 维护商处理完回传：处理结果 + 处理人（multipart 表单可带整改后照片，可不带）。 */
public class RectifyRepairRequest {
    /** 处理结果说明 *（怎么处理的、换了什么件） */
    private String repairResult;
    /** 维护商处理人（留空取当前登录账号） */
    private String operatorName;

    public String getRepairResult() { return repairResult; }
    public void setRepairResult(String repairResult) { this.repairResult = repairResult; }
    public String getOperatorName() { return operatorName; }
    public void setOperatorName(String operatorName) { this.operatorName = operatorName; }
}
