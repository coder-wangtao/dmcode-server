package com.wangtao.domain.agent.service.model;

public class ModelConfigException extends RuntimeException {

    public ModelConfigException(String message) {
        super(message);
    }

    public ModelConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
