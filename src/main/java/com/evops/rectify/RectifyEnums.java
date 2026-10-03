package com.evops.rectify;

/**
 * F6 异常上报与整改销项状态口径。
 *
 * 三张口径：
 * - 异常上报状态（挂在整改单上）：REPORTED 已上报待派单 / DISPATCHED 已派单整改中
 *   / REPAIRED 已回传待复勘 / CLOSED 已销项；复勘驳回退回 DISPATCHED 重处理。
 * - 异常上报版本：每流转一次 version_no +1，事件表带快照可翻；关闭即冻结。
 * - 留证照片阶段：REPORT 现场上报 / REPAIR 整改回传；单子关闭前一律不许删。
 */
public final class RectifyEnums {
    private RectifyEnums() {}

    /** 已上报待派单 */
    public static final String STATUS_REPORTED = "REPORTED";
    /** 已派单整改中（含复勘驳回后的重处理） */
    public static final String STATUS_DISPATCHED = "DISPATCHED";
    /** 维护商已回传，待复勘 */
    public static final String STATUS_REPAIRED = "REPAIRED";
    /** 复勘通过已销项（关闭冻结） */
    public static final String STATUS_CLOSED = "CLOSED";

    /** 照片阶段：现场上报 */
    public static final String PHASE_REPORT = "REPORT";
    /** 照片阶段：整改回传 */
    public static final String PHASE_REPAIR = "REPAIR";

    // ---- 事件类型（只追加留底） ----
    public static final String EVENT_REPORT = "REPORT";
    public static final String EVENT_DISPATCH = "DISPATCH";
    public static final String EVENT_REPAIR = "REPAIR";
    public static final String EVENT_REINSPECT_PASS = "REINSPECT_PASS";
    public static final String EVENT_REINSPECT_REJECT = "REINSPECT_REJECT";
    public static final String EVENT_ESCALATE = "ESCALATE";
    public static final String EVENT_PHOTO_DELETE = "PHOTO_DELETE";

    /** 异常类型口径（牌面翘边等），存代码，展示走 label */
    public static final String TYPE_PANEL_LIFT = "PANEL_LIFT";
    public static final String TYPE_PANEL_DAMAGE = "PANEL_DAMAGE";
    public static final String TYPE_LIGHT_FAULT = "LIGHT_FAULT";
    public static final String TYPE_STRUCTURE_RISK = "STRUCTURE_RISK";
    public static final String TYPE_OTHER = "OTHER";

    public static String statusLabel(String s) {
        switch (s) {
            case STATUS_REPORTED: return "已上报待派单";
            case STATUS_DISPATCHED: return "已派单整改中";
            case STATUS_REPAIRED: return "已回传待复勘";
            case STATUS_CLOSED: return "已销项";
            default: return s;
        }
    }

    public static String typeLabel(String code) {
        if (code == null) {
            return "";
        }
        switch (code.trim().toUpperCase()) {
            case TYPE_PANEL_LIFT: return "牌面翘边";
            case TYPE_PANEL_DAMAGE: return "牌面破损";
            case TYPE_LIGHT_FAULT: return "亮灯故障";
            case TYPE_STRUCTURE_RISK: return "结构隐患";
            case TYPE_OTHER: return "其他异常";
            default: return code;
        }
    }

    public static String phaseLabel(String s) {
        if (PHASE_REPORT.equals(s)) {
            return "现场上报";
        }
        if (PHASE_REPAIR.equals(s)) {
            return "整改回传";
        }
        return s;
    }
}
