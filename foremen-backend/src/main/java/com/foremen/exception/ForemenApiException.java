package com.foremen.exception;

import lombok.Getter;
import org.springframework.http.HttpStatusCode;

@Getter
public class ForemenApiException extends RuntimeException {

    private final HttpStatusCode status;
    private final String messageCode;
    private final Object[] messageParams;

    public ForemenApiException(HttpStatusCode status, String messageCode, Object... messageParams) {
        super(messageCode);
        this.status = status;
        this.messageCode = messageCode;
        this.messageParams = messageParams != null ? messageParams : new Object[0];
    }

    public ForemenApiException(HttpStatusCode status, String messageCode) {
        this(status, messageCode, new Object[0]);
    }
}
