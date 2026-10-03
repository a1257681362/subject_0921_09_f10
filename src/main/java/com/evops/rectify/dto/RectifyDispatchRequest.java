package com.evops.rectify.dto;

import javax.validation.constraints.NotBlank;

/** 派单：系统把整改单派给维护商，期限按配置给（可临时覆盖）。 */
public class RectifyDispatchRequest {
    /** 承派维护商 * */
    @NotBlank(message = "承派维护商不能为空")
    private String vendorName;
    /** 维护商联系方式 */
    private String vendorContact;
    /** 派单经手人（调度，留空取当前登录账号） */
    private String operatorName;
    /** 临时整改期限（小时），留空走 rectify.deadline-hours 配置 */
    private Integer deadlineHours;
    private String remark;

    public String getVendorName() { return vendorName; }
    public void setVendorName(String vendorName) { this.vendorName = vendorName; }
    public String getVendorContact() { return vendorContact; }
    public void setVendorContact(String vendorContact) { this.vendorContact = vendorContact; }
    public String getOperatorName() { return operatorName; }
    public void setOperatorName(String operatorName) { this.operatorName = operatorName; }
    public Integer getDeadlineHours() { return deadlineHours; }
    public void setDeadlineHours(Integer deadlineHours) { this.deadlineHours = deadlineHours; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
