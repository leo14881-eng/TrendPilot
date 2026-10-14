package com.trendpilot.btcdaily;

/** 市场 / 宏观数据获取失败：只记缺失，不影响计划的其他部分。 */
public class ContextDataException extends RuntimeException {

    public ContextDataException(String message) {
        super(message);
    }

    public ContextDataException(String message, Throwable cause) {
        super(message, cause);
    }
}
