package com.trendpilot.research;

/** 研究数据获取失败。研究资料缺失只记为缺口，不能伪造。 */
public class ResearchDataException extends RuntimeException {

    public ResearchDataException(String message) {
        super(message);
    }

    public ResearchDataException(String message, Throwable cause) {
        super(message, cause);
    }
}
