package com.marketingpanel.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private final MessageSource messages;

    public GlobalExceptionHandler(MessageSource messages) {
        this.messages = messages;
    }

    private ResponseEntity<Map<String, Object>> build(ErrorCode code, String message, List<FieldErr> fe,
                                                     Map<String, Object> extra, Integer retryAfter) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", code.status.value());
        body.put("code", code.name());
        body.put("message", message);
        body.put("fieldErrors", fe);
        body.putAll(extra);
        body.put("traceId", MDC.get("traceId"));
        var b = ResponseEntity.status(code.status);
        if (retryAfter != null) b.header(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter));
        return b.body(body);
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> api(ApiException e) {
        return build(e.code(), e.getMessage(), e.fieldErrors(), e.extra(), e.retryAfter());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> invalid(MethodArgumentNotValidException e) {
        List<FieldErr> out = new ArrayList<>();
        for (FieldError fe : e.getBindingResult().getFieldErrors()) {
            String key = fe.getCode();
            try {
                ConstraintViolation<?> cv = fe.unwrap(ConstraintViolation.class);
                String t = cv.getMessageTemplate();
                if (t.startsWith("{") && t.endsWith("}")) key = t.substring(1, t.length() - 1);
            } catch (Exception ignored) {
                // not a bean validation error
            }
            out.add(new FieldErr(fe.getField(), key, fe.getDefaultMessage()));
        }
        return build(ErrorCode.VALIDATION_FAILED, text("validation.failed", "Please fix the highlighted fields."), out, Map.of(), null);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, Object>> constraint(ConstraintViolationException e) {
        List<FieldErr> out = new ArrayList<>();
        for (ConstraintViolation<?> v : e.getConstraintViolations()) {
            String path = v.getPropertyPath().toString();
            String field = path.contains(".") ? path.substring(path.lastIndexOf('.') + 1) : path;
            String t = v.getMessageTemplate();
            out.add(new FieldErr(field, t.startsWith("{") ? t.substring(1, t.length() - 1) : t, v.getMessage()));
        }
        return build(ErrorCode.VALIDATION_FAILED, text("validation.failed", "Please fix the highlighted fields."), out, Map.of(), null);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MissingServletRequestParameterException.class,
            HttpMediaTypeNotSupportedException.class, IllegalArgumentException.class})
    public ResponseEntity<Map<String, Object>> badRequest(Exception e) {
        return build(ErrorCode.VALIDATION_FAILED, "The request could not be understood.", List.of(), Map.of(), null);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> tooBig(Exception e) {
        return build(ErrorCode.VALIDATION_FAILED, "The file is too large.", List.of(), Map.of(), null);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> denied(AccessDeniedException e) {
        var a = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (a == null || a instanceof org.springframework.security.authentication.AnonymousAuthenticationToken)
            return build(ErrorCode.UNAUTHORIZED, "Please log in.", List.of(), Map.of(), null);
        return build(ErrorCode.FORBIDDEN, "You do not have access to this.", List.of(), Map.of(), null);
    }

    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<Map<String, Object>> dup(DuplicateKeyException e) {
        return build(ErrorCode.DUPLICATE, "This value already exists.", List.of(), Map.of(), null);
    }

    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public ResponseEntity<Map<String, Object>> noHandler(Exception e) {
        return build(ErrorCode.NOT_FOUND, "Not found.", List.of(), Map.of(), null);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> method(HttpRequestMethodNotSupportedException e) {
        return ResponseEntity.status(405).body(Map.of("status", 405, "code", "METHOD_NOT_ALLOWED",
                "message", "Method not allowed.", "traceId", String.valueOf(MDC.get("traceId"))));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> other(Exception e, HttpServletRequest req) {
        log.error("Unhandled error on {} {}", req.getMethod(), req.getRequestURI(), e);
        return build(ErrorCode.INTERNAL_ERROR, "Something went wrong. Please try again.", List.of(), Map.of(), null);
    }

    private String text(String key, String def) {
        return messages.getMessage(key, null, def, LocaleContextHolder.getLocale());
    }
}
