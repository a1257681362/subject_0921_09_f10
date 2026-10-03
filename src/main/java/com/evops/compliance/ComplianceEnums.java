package com.evops.compliance;

/**
 * F10 受限行业类目名单状态口径。
 *
 * - 类目名单状态（版本状态）：DRAFT 编制中 / EFFECTIVE 生效中 / SUPERSEDED 已被新版取代；
 *   生效版不可作废（不留无名单空窗），调整只能另起新版再发布。
 * - 冻结记录处置状态：FROZEN 冻结中 / RELEASED 已解冻 / FORCE_CLOSED 强制落架。
 */
public final class ComplianceEnums {
    private ComplianceEnums() {}

    /** 类目名单版本状态 */
    public static final String VERSION_DRAFT = "DRAFT";
    public static final String VERSION_EFFECTIVE = "EFFECTIVE";
    public static final String VERSION_SUPERSEDED = "SUPERSEDED";

    /** 冻结记录处置状态 */
    public static final String RECORD_FROZEN = "FROZEN";
    public static final String RECORD_RELEASED = "RELEASED";
    public static final String RECORD_FORCE_CLOSED = "FORCE_CLOSED";

    public static String versionStatusLabel(String s) {
        switch (s) {
            case VERSION_DRAFT: return "编制中";
            case VERSION_EFFECTIVE: return "生效中";
            case VERSION_SUPERSEDED: return "已被新版取代";
            default: return s;
        }
    }

    public static String recordStatusLabel(String s) {
        switch (s) {
            case RECORD_FROZEN: return "冻结中";
            case RECORD_RELEASED: return "已解冻";
            case RECORD_FORCE_CLOSED: return "强制落架";
            default: return s;
        }
    }
}
