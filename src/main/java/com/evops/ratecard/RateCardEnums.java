package com.evops.ratecard;

/** 年度刊例状态口径。 */
public final class RateCardEnums {
    private RateCardEnums() {}

    /** 年度刊例状态：编制中 / 生效中 / 已封存（年度封存后不可再起新版、不可报价） */
    public static final String CARD_DRAFT = "DRAFT";
    public static final String CARD_EFFECTIVE = "EFFECTIVE";
    public static final String CARD_CLOSED = "CLOSED";

    /** 刊例版本状态：草稿 / 生效中 / 已被新版取代 / 已作废 */
    public static final String VERSION_DRAFT = "DRAFT";
    public static final String VERSION_EFFECTIVE = "EFFECTIVE";
    public static final String VERSION_SUPERSEDED = "SUPERSEDED";
    public static final String VERSION_REVOKED = "REVOKED";

    /** 报价单状态：已提交（落单即快照，无任何改写路径） */
    public static final String QUOTE_SUBMITTED = "SUBMITTED";

    /** 新建年度刊例 v1 的版本号 */
    public static final int FIRST_VERSION_NO = 1;
}
