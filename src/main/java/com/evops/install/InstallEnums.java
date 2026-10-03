package com.evops.install;

/**
 * F8 安装派单与资质匹配状态口径。
 *
 * 三张口径：
 * - 资质匹配状态（挂在安装派单上）：PENDING 待匹配 / ASSIGNED 已派单 / ARRIVED 已到场打卡
 *   / COMPLETED 已完工回执 / CANCELLED 已取消；
 * - 资质匹配版本：每流转一次 version_no +1，事件表带快照可翻；自动匹配、人工补匹配、改期、
 *   到场、完工、对冲取消都逐事件留底；
 * - 安装队资质状态：安装队 ACTIVE 在册 / DISABLED 停用；单张资质 VALID 在册有效 / EXPIRED 已过期
 *   / REVOKED 已吊销，高空点位以「存在状态 VALID 且 expiry_date 不早于计划日的高空作业证」为硬门槛。
 */
public final class InstallEnums {
    private InstallEnums() {}

    // ---- 资质匹配状态（派单状态） ----
    /** 待匹配：上刊确认已自动生成派单，但暂无「资质合格且该时段空闲」的安装队 */
    public static final String STATUS_PENDING = "PENDING";
    /** 已派单：资质与档期都匹配上，队与计划时段已定 */
    public static final String STATUS_ASSIGNED = "ASSIGNED";
    /** 已到场打卡：师傅到场拍照，等待完工回执 */
    public static final String STATUS_ARRIVED = "ARRIVED";
    /** 已完工回执：完工回传，上刊单随状态走，计入当月完工量 */
    public static final String STATUS_COMPLETED = "COMPLETED";
    /** 已取消：上刊单对冲更正联动取消，释放该队档期 */
    public static final String STATUS_CANCELLED = "CANCELLED";

    // ---- 安装队状态 ----
    public static final String TEAM_ACTIVE = "ACTIVE";
    public static final String TEAM_DISABLED = "DISABLED";

    // ---- 单张资质状态 ----
    /** 在册有效（是否在有效期仍以 expiry_date 实时比较） */
    public static final String CERT_VALID = "VALID";
    /** 已过期（到期次日起） */
    public static final String CERT_EXPIRED = "EXPIRED";
    /** 已吊销（作废不再认可） */
    public static final String CERT_REVOKED = "REVOKED";

    // ---- 资质类型 ----
    /** 高空作业证：高空点位的硬门槛 */
    public static final String CERT_WORK_AT_HEIGHT = "WORK_AT_HEIGHT";
    /** 电工作业证（普通资质，记录用） */
    public static final String CERT_ELECTRICIAN = "ELECTRICIAN";
    /** 高处安装/其他安全证（记录用） */
    public static final String CERT_SAFETY_OTHER = "SAFETY_OTHER";

    // ---- 点位作业性质（镜像底账 t_site.work_type 口径，便于安装域内引用） ----
    public static final String WORK_NORMAL = "NORMAL";
    public static final String WORK_HIGH = "HIGH_ALTITUDE";

    // ---- 照片阶段 ----
    /** 到场打卡（必传照片） */
    public static final String PHASE_ARRIVAL = "ARRIVAL";
    /** 完工回执 */
    public static final String PHASE_COMPLETION = "COMPLETION";

    // ---- 事件类型（只追加留底） ----
    public static final String EVENT_AUTO_CREATE = "AUTO_CREATE";
    public static final String EVENT_MATCH = "MATCH";
    public static final String EVENT_RESCHEDULE = "RESCHEDULE";
    public static final String EVENT_ARRIVE = "ARRIVE";
    public static final String EVENT_COMPLETE = "COMPLETE";
    public static final String EVENT_CANCEL = "CANCEL";
    /** 改期通知到队 */
    public static final String EVENT_NOTIFY = "NOTIFY";

    // ---- 匹配结果明细口径（MATCH 事件 detail 用） ----
    public static final String MATCH_AUTO = "AUTO";
    public static final String MATCH_MANUAL = "MANUAL";

    public static String statusLabel(String s) {
        if (s == null) {
            return "";
        }
        switch (s) {
            case STATUS_PENDING: return "待匹配";
            case STATUS_ASSIGNED: return "已派单";
            case STATUS_ARRIVED: return "已到场打卡";
            case STATUS_COMPLETED: return "已完工";
            case STATUS_CANCELLED: return "已取消";
            default: return s;
        }
    }

    public static String teamStatusLabel(String s) {
        if (TEAM_ACTIVE.equals(s)) {
            return "在册";
        }
        if (TEAM_DISABLED.equals(s)) {
            return "停用";
        }
        return s;
    }

    public static String certStatusLabel(String s) {
        if (s == null) {
            return "";
        }
        switch (s) {
            case CERT_VALID: return "在册有效";
            case CERT_EXPIRED: return "已过期";
            case CERT_REVOKED: return "已吊销";
            default: return s;
        }
    }

    public static String certTypeLabel(String code) {
        if (code == null) {
            return "";
        }
        switch (code.trim().toUpperCase()) {
            case CERT_WORK_AT_HEIGHT: return "高空作业证";
            case CERT_ELECTRICIAN: return "电工作业证";
            case CERT_SAFETY_OTHER: return "其他安全作业证";
            default: return code;
        }
    }

    public static String workTypeLabel(String code) {
        if (isHighAltitude(code)) {
            return "高空点位";
        }
        return "普通点位";
    }

    /** 是否高空作业点位。 */
    public static boolean isHighAltitude(String workType) {
        return "HIGH_ALTITUDE".equals(workType);
    }
}
