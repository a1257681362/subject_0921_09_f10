package com.evops.playlist;

/**
 * F7 屏体播放列表排片的状态口径。
 *
 * 三张口径：
 * - 播放列表模块（挂在 t_playlist 上）：EDITING 编排中 / LOCKED 已锁版（只要存在任一周锁版即视为已进入锁版管理）。
 * - 播放列表版本（版本链，只追加）：DRAFT 草稿可反复保存；锁版后为 LOCKED；锁版后改动先进 PENDING，
 *   审批通过后新草稿锁版生效、被取代版本置 SUPERSEDED；驳回置 REJECTED 留底。
 * - 播放列表状态（播放器实况指针 + 告警）：到点切换成功写 live 指针；切换失败/空档/素材缺失写告警，不静默。
 *
 * 跨零点时段一律先按自然日切成两段再做冲突比较与到点切换。
 */
public final class PlaylistEnums {
    private PlaylistEnums() {}

    // ---- 播放列表主档状态 ----
    /** 编排中（还没有任何周锁版） */
    public static final String PLAYLIST_EDITING = "EDITING";
    /** 已锁版（至少一周已锁，后续周仍可编排） */
    public static final String PLAYLIST_LOCKED = "LOCKED";

    // ---- 播放列表版本状态 ----
    /** 草稿：可反复整表保存、复制、顺延 */
    public static final String VERSION_DRAFT = "DRAFT";
    /** 已锁版：到点按它切换，不许直接改 */
    public static final String VERSION_LOCKED = "LOCKED";
    /** 锁版后改单申请，待审批 */
    public static final String VERSION_PENDING = "PENDING";
    /** 审批驳回，留底 */
    public static final String VERSION_REJECTED = "REJECTED";
    /** 已被新锁版版本取代，历史可翻 */
    public static final String VERSION_SUPERSEDED = "SUPERSEDED";

    // ---- 版本变更类型 ----
    public static final String CHANGE_CREATE = "CREATE";
    public static final String CHANGE_EDIT = "EDIT";
    public static final String CHANGE_COPY = "COPY";
    public static final String CHANGE_SHIFT = "SHIFT";
    public static final String CHANGE_LOCK = "LOCK";
    public static final String CHANGE_AUTO_LOCK = "AUTO_LOCK";
    public static final String CHANGE_CHANGE_REQUEST = "CHANGE_REQUEST";
    public static final String CHANGE_APPROVE = "APPROVE";

    // ---- 周锁版来源 ----
    public static final String LOCK_MANUAL = "MANUAL";
    public static final String LOCK_AUTO = "AUTO";

    // ---- 事件类型（只追加留底） ----
    public static final String EVENT_CREATE = "CREATE";
    public static final String EVENT_SAVE = "SAVE";
    public static final String EVENT_COPY = "COPY";
    public static final String EVENT_SHIFT = "SHIFT";
    public static final String EVENT_LOCK = "LOCK";
    public static final String EVENT_AUTO_LOCK = "AUTO_LOCK";
    public static final String EVENT_CHANGE_REQUEST = "CHANGE_REQUEST";
    public static final String EVENT_APPROVE = "APPROVE";
    public static final String EVENT_REJECT = "REJECT";
    public static final String EVENT_SWITCH = "SWITCH";

    // ---- 告警类型 ----
    /** 到点切换失败 */
    public static final String ALERT_SWITCH_FAILED = "SWITCH_FAILED";
    /** 同日相邻时段间空档（该时点无有效排片） */
    public static final String ALERT_GAP = "GAP";
    /** 时段引用的素材在库里找不到（被删/未建档） */
    public static final String ALERT_MISSING_ASSET = "MISSING_ASSET";
    /** 下周无任何排片，周五下班前无法锁版 */
    public static final String ALERT_WEEK_EMPTY = "WEEK_EMPTY";
    /** 周五自动锁版失败（如编排里引用的素材已失效） */
    public static final String ALERT_LOCK_FAILED = "LOCK_FAILED";
    /** 下周锁版改单仍待审批，自动锁版时刻拿不到可生效版本 */
    public static final String ALERT_PENDING_CHANGE = "PENDING_CHANGE";

    // ---- 告警状态 ----
    public static final String ALERT_OPEN = "OPEN";
    public static final String ALERT_RESOLVED = "RESOLVED";

    public static String playlistStatusLabel(String s) {
        if (PLAYLIST_LOCKED.equals(s)) {
            return "已锁版";
        }
        return "编排中";
    }

    public static String versionStatusLabel(String s) {
        switch (s) {
            case VERSION_DRAFT: return "草稿";
            case VERSION_LOCKED: return "已锁版";
            case VERSION_PENDING: return "待审批";
            case VERSION_REJECTED: return "已驳回";
            case VERSION_SUPERSEDED: return "已被新版取代";
            default: return s;
        }
    }

    public static String changeTypeLabel(String s) {
        switch (s) {
            case CHANGE_CREATE: return "建档";
            case CHANGE_EDIT: return "编排保存";
            case CHANGE_COPY: return "整段复制";
            case CHANGE_SHIFT: return "整段顺延";
            case CHANGE_LOCK: return "人工锁版";
            case CHANGE_AUTO_LOCK: return "周五自动锁版";
            case CHANGE_CHANGE_REQUEST: return "锁后改单申请";
            case CHANGE_APPROVE: return "审批通过";
            default: return s;
        }
    }

    public static String alertTypeLabel(String s) {
        switch (s) {
            case ALERT_SWITCH_FAILED: return "切换失败";
            case ALERT_GAP: return "播放空档";
            case ALERT_MISSING_ASSET: return "素材缺失";
            case ALERT_WEEK_EMPTY: return "下周无排片";
            case ALERT_LOCK_FAILED: return "自动锁版失败";
            case ALERT_PENDING_CHANGE: return "改单待审批";
            default: return s;
        }
    }
}
