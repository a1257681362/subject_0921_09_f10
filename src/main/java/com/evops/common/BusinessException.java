package com.evops.common;

/** 业务规则违例：消息可直接透传给前端（例如编号重复、档期冲突、单据已关闭）。 */
public class BusinessException extends RuntimeException {
    public BusinessException(String message) {
        super(message);
    }

    public BusinessException(String message, Throwable cause) {
        super(message, cause);
    }
}
