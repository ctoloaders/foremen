package com.foremen.exception;

import org.springframework.http.HttpStatus;

public class ForemenValidationException extends ForemenApiException {

    public ForemenValidationException(String messageCode, Object... messageParams) {
        super(HttpStatus.BAD_REQUEST, messageCode, messageParams);
    }
}
