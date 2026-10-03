package com.evops.credit;

/**
 * F5 客户授信与回款核销状态口径。
 *
 * 三张状态机：
 * - 信用额度状态（挂在客户上）：ACTIVE 在档 / FROZEN 冻结；
 * - 信用额度版本：PENDING 待审批 / EFFECTIVE 生效 / REJECTED 已驳回 / SUPERSEDED 已被新版本取代；
 * - 占用与回款：占用 ACTIVE / RELEASED 落架转应收 / REVERSED 对冲；核销 UNSETTLED / PARTIAL / SETTLED。
 */
public final class CreditEnums {
    private CreditEnums() {}

    /** 客户信用额度状态 */
    public static final String CUSTOMER_ACTIVE = "ACTIVE";
    public static final String CUSTOMER_FROZEN = "FROZEN";

    /** 额度变更类型：建档 / 调额 / 冻结 / 解冻 */
    public static final String CHANGE_INITIAL = "INITIAL";
    public static final String CHANGE_ADJUST = "ADJUST";
    public static final String CHANGE_FREEZE = "FREEZE";
    public static final String CHANGE_UNFREEZE = "UNFREEZE";

    /** 信用额度版本状态 */
    public static final String VERSION_PENDING = "PENDING";
    public static final String VERSION_EFFECTIVE = "EFFECTIVE";
    public static final String VERSION_REJECTED = "REJECTED";
    public static final String VERSION_SUPERSEDED = "SUPERSEDED";

    /** 占用状态：挂起占用中 / 落架撤刊（转应收）/ 对冲更正（不转应收） */
    public static final String OCC_ACTIVE = "ACTIVE";
    public static final String OCC_RELEASED = "RELEASED";
    public static final String OCC_REVERSED = "REVERSED";

    /** 核销状态 */
    public static final String SETTLE_UNSETTLED = "UNSETTLED";
    public static final String SETTLE_PARTIAL = "PARTIAL";
    public static final String SETTLE_SETTLED = "SETTLED";

    /** 回款凭证状态：已登记待核销 / 部分核销 / 已核销完 */
    public static final String PAYMENT_RECEIVED = "RECEIVED";
    public static final String PAYMENT_PARTIAL = "PARTIAL";
    public static final String PAYMENT_SETTLED = "SETTLED";

    /** 建档版本号 */
    public static final int FIRST_VERSION_NO = 1;

    public static String customerStatusLabel(String s) {
        switch (s) {
            case CUSTOMER_ACTIVE: return "在档";
            case CUSTOMER_FROZEN: return "冻结";
            default: return s;
        }
    }

    public static String versionStatusLabel(String s) {
        switch (s) {
            case VERSION_PENDING: return "待审批";
            case VERSION_EFFECTIVE: return "生效";
            case VERSION_REJECTED: return "已驳回";
            case VERSION_SUPERSEDED: return "已被新版本取代";
            default: return s;
        }
    }

    public static String changeTypeLabel(String s) {
        switch (s) {
            case CHANGE_INITIAL: return "建档授信";
            case CHANGE_ADJUST: return "额度调整";
            case CHANGE_FREEZE: return "冻结";
            case CHANGE_UNFREEZE: return "解冻";
            default: return s;
        }
    }

    public static String occupationStatusLabel(String s) {
        switch (s) {
            case OCC_ACTIVE: return "占用中";
            case OCC_RELEASED: return "已撤刊转应收";
            case OCC_REVERSED: return "已对冲";
            default: return s;
        }
    }

    public static String settleStatusLabel(String s) {
        switch (s) {
            case SETTLE_UNSETTLED: return "未核销";
            case SETTLE_PARTIAL: return "部分核销";
            case SETTLE_SETTLED: return "已结清";
            default: return s;
        }
    }
}
