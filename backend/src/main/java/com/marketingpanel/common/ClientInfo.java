package com.marketingpanel.common;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

public final class ClientInfo {
    private ClientInfo() {}

    public static HttpServletRequest request() {
        RequestAttributes ra = RequestContextHolder.getRequestAttributes();
        return ra instanceof ServletRequestAttributes s ? s.getRequest() : null;
    }

    public static String ip() {
        HttpServletRequest r = request();
        return r == null ? null : r.getRemoteAddr();
    }

    public static String userAgent() {
        HttpServletRequest r = request();
        String ua = r == null ? null : r.getHeader("User-Agent");
        return ua == null ? null : (ua.length() > 255 ? ua.substring(0, 255) : ua);
    }

    public static String ip(HttpServletRequest r) {
        return r.getRemoteAddr();
    }

    public static String ipHash(String ip, String salt) {
        return Hashing.sha256(salt + "|" + ip);
    }
}
