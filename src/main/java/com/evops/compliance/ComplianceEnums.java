package com.evops.compliance;

/**
 * F10 受限行业类目名单状态口径。
 *
 * - 类目条目状态：ACTIVE 在档（建档可选）/ DISABLED 停用（历史客户留名，建档不可再选）；
 * - 名单版本：EFFECTIVE 生效中 / SUPERSEDED 已被新版取代（每次维护自动落版，即时生效）；
 * - 维护动作：INITIAL 首批预置 / ADD_CATEGORY 新增 / RESTRICT 设为受限 / RELEASE 解除受限
 *             / MODIFY 改名改依据 / DISABLE 停用。
 */
public final class ComplianceEnums {
    private ComplianceEnums() {}

    /** 类目条目状态 */
    public static final String CATEGORY_ACTIVE = "ACTIVE";
    public static final String CATEGORY_DISABLED = "DISABLED";

    /** 名单版本状态 */
    public static final String VERSION_EFFECTIVE = "EFFECTIVE";
    public static final String VERSION_SUPERSEDED = "SUPERSEDED";

    /** 维护动作（版本 change_type / 操作流水 action 共用） */
    public static final String CHANGE_INITIAL = "INITIAL";
    public static final String CHANGE_ADD = "ADD_CATEGORY";
    public static final String CHANGE_RESTRICT = "RESTRICT";
    public static final String CHANGE_RELEASE = "RELEASE";
    public static final String CHANGE_MODIFY = "MODIFY";
    public static final String CHANGE_DISABLE = "DISABLE";

    public static String categoryStatusLabel(String s) {
        switch (s) {
            case CATEGORY_ACTIVE: return "在档";
            case CATEGORY_DISABLED: return "停用";
            default: return s;
        }
    }

    public static String changeTypeLabel(String s) {
        switch (s) {
            case CHANGE_INITIAL: return "首批预置";
            case CHANGE_ADD: return "新增类目";
            case CHANGE_RESTRICT: return "设为受限";
            case CHANGE_RELEASE: return "解除受限";
            case CHANGE_MODIFY: return "修改类目";
            case CHANGE_DISABLE: return "停用类目";
            default: return s;
        }
    }
}
