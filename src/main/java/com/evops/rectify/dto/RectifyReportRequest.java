package com.evops.rectify.dto;

import javax.validation.constraints.NotBlank;

/** 现场异常上报（multipart 表单：业务字段 + 可选照片文件）。 */
public class RectifyReportRequest {
    /** 点位编号 * */
    @NotBlank(message = "点位编号不能为空")
    private String siteNo;
    /** 异常类型 *（PANEL_LIFT 牌面翘边 / PANEL_DAMAGE 牌面破损 / LIGHT_FAULT 亮灯故障 / STRUCTURE_RISK 结构隐患 / OTHER 其他） */
    @NotBlank(message = "异常类型不能为空")
    private String abnormalType;
    /** 异常情况说明（翘边位置、数量等） */
    private String abnormalDesc;
    /** 上报巡查员（留空取当前登录账号） */
    private String reporterName;
    private String remark;

    public String getSiteNo() { return siteNo; }
    public void setSiteNo(String siteNo) { this.siteNo = siteNo; }
    public String getAbnormalType() { return abnormalType; }
    public void setAbnormalType(String abnormalType) { this.abnormalType = abnormalType; }
    public String getAbnormalDesc() { return abnormalDesc; }
    public void setAbnormalDesc(String abnormalDesc) { this.abnormalDesc = abnormalDesc; }
    public String getReporterName() { return reporterName; }
    public void setReporterName(String reporterName) { this.reporterName = reporterName; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
