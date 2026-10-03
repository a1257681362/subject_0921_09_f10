package com.evops.agent;

/**
 * F9 代理协议与结算快照状态口径。
 *
 * 三张状态机：
 * - 代理档案：ACTIVE 在册 / DISABLED 停用（历史账单照翻，不参与新结算）；
 * - 代理协议：ACTIVE 生效中 / TERMINATED 已终止（终止从下一结算期生效）；
 * - 协议版本：PENDING 待生效 / EFFECTIVE 生效中 / SUPERSEDED 已被新版取代。
 * 结算单只有 SETTLED 已结算一态：确认即冻结，已结算期不重算。
 */
public final class AgentEnums {
    private AgentEnums() {}

    /** 代理档案状态 */
    public static final String AGENT_ACTIVE = "ACTIVE";
    public static final String AGENT_DISABLED = "DISABLED";

    /** 代理协议状态 */
    public static final String AGREEMENT_ACTIVE = "ACTIVE";
    public static final String AGREEMENT_TERMINATED = "TERMINATED";

    /** 协议变更类型：建档挂协议 / 中途调比例 / 终止 */
    public static final String CHANGE_INITIAL = "INITIAL";
    public static final String CHANGE_ADJUST = "ADJUST";
    public static final String CHANGE_TERMINATE = "TERMINATE";

    /** 协议版本状态 */
    public static final String VERSION_PENDING = "PENDING";
    public static final String VERSION_EFFECTIVE = "EFFECTIVE";
    public static final String VERSION_SUPERSEDED = "SUPERSEDED";

    /** 结算单状态：确认即冻结 */
    public static final String SETTLEMENT_SETTLED = "SETTLED";

    /** 尾差兜底方：平台方（分账合计必须与流水对平，差的分由平台兜底吸收） */
    public static final String TAIL_PARTY_PLATFORM = "PLATFORM";

    /** 建档版本号 */
    public static final int FIRST_VERSION_NO = 1;

    public static String agentStatusLabel(String s) {
        switch (s) {
            case AGENT_ACTIVE: return "在册";
            case AGENT_DISABLED: return "停用";
            default: return s;
        }
    }

    public static String agreementStatusLabel(String s) {
        switch (s) {
            case AGREEMENT_ACTIVE: return "生效中";
            case AGREEMENT_TERMINATED: return "已终止";
            default: return s;
        }
    }

    public static String versionStatusLabel(String s) {
        switch (s) {
            case VERSION_PENDING: return "待生效";
            case VERSION_EFFECTIVE: return "生效中";
            case VERSION_SUPERSEDED: return "已被新版取代";
            default: return s;
        }
    }

    public static String changeTypeLabel(String s) {
        switch (s) {
            case CHANGE_INITIAL: return "建档挂协议";
            case CHANGE_ADJUST: return "比例调整";
            case CHANGE_TERMINATE: return "终止";
            default: return s;
        }
    }
}
