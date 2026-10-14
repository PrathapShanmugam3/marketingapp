package com.marketingpanel.common;

import org.springframework.http.HttpStatus;

/** Appendix A of the SRS. */
public enum ErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED),
    OTP_INVALID(HttpStatus.BAD_REQUEST),
    ACCOUNT_LOCKED(HttpStatus.LOCKED),
    FORBIDDEN(HttpStatus.FORBIDDEN),
    PLAN_LIMIT_REACHED(HttpStatus.FORBIDDEN),
    NOT_FOUND(HttpStatus.NOT_FOUND),
    DUPLICATE(HttpStatus.CONFLICT),
    INVALID_STATE(HttpStatus.CONFLICT),
    INSUFFICIENT_BALANCE(HttpStatus.PAYMENT_REQUIRED),
    WHATSAPP_NOT_CONNECTED(HttpStatus.CONFLICT),
    KYC_REQUIRED(HttpStatus.FORBIDDEN),
    PAYOUT_LIMIT(HttpStatus.UNPROCESSABLE_ENTITY),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS),
    GATEWAY_ERROR(HttpStatus.BAD_GATEWAY),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    public final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }
}
