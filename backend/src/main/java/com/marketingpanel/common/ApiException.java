package com.marketingpanel.common;

import java.util.List;
import java.util.Map;

public class ApiException extends RuntimeException {
    private final ErrorCode code;
    private final List<FieldErr> fieldErrors;
    private final Map<String, Object> extra;
    private final Integer retryAfterSeconds;

    public ApiException(ErrorCode code, String message) {
        this(code, message, List.of(), Map.of(), null);
    }

    public ApiException(ErrorCode code, String message, List<FieldErr> fieldErrors, Map<String, Object> extra, Integer retryAfter) {
        super(message);
        this.code = code;
        this.fieldErrors = fieldErrors;
        this.extra = extra;
        this.retryAfterSeconds = retryAfter;
    }

    public ErrorCode code() {
        return code;
    }

    public List<FieldErr> fieldErrors() {
        return fieldErrors;
    }

    public Map<String, Object> extra() {
        return extra;
    }

    public Integer retryAfter() {
        return retryAfterSeconds;
    }

    public static ApiException notFound(String what) {
        return new ApiException(ErrorCode.NOT_FOUND, what + " not found.");
    }

    public static ApiException forbidden(String msg) {
        return new ApiException(ErrorCode.FORBIDDEN, msg);
    }

    public static ApiException forbidden() {
        return forbidden("You do not have access to this.");
    }

    public static ApiException unauthorized(String msg) {
        return new ApiException(ErrorCode.UNAUTHORIZED, msg);
    }

    public static ApiException duplicate(String msg) {
        return new ApiException(ErrorCode.DUPLICATE, msg);
    }

    public static ApiException duplicate(String field, String key, String msg) {
        return new ApiException(ErrorCode.DUPLICATE, msg, List.of(new FieldErr(field, key, msg)), Map.of(), null);
    }

    public static ApiException state(String msg) {
        return new ApiException(ErrorCode.INVALID_STATE, msg);
    }

    public static ApiException planLimit(String msg) {
        return new ApiException(ErrorCode.PLAN_LIMIT_REACHED, msg);
    }

    public static ApiException invalid(String field, String key, String msg) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, "Please fix the highlighted fields.",
                List.of(new FieldErr(field, key, msg)), Map.of(), null);
    }

    public static ApiException invalid(List<FieldErr> errs) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, "Please fix the highlighted fields.", errs, Map.of(), null);
    }

    public static ApiException badRequest(String msg) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, msg);
    }

    public static ApiException gateway(String msg) {
        return new ApiException(ErrorCode.GATEWAY_ERROR, msg);
    }

    public static ApiException insufficient(String msg) {
        return new ApiException(ErrorCode.INSUFFICIENT_BALANCE, msg);
    }

    public static ApiException rateLimited(int retryAfter) {
        return new ApiException(ErrorCode.RATE_LIMITED, "Too many requests. Try again in " + retryAfter + " seconds.",
                List.of(), Map.of(), retryAfter);
    }
}
