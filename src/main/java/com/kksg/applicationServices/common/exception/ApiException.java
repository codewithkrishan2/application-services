package com.kksg.applicationServices.common.exception;

import java.io.Serial;

public class ApiException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public ApiException() {
        super();
    }

    public ApiException(String message) {
        super(message);
    }

    public ApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
