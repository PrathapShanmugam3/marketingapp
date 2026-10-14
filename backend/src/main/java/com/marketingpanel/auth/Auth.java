package com.marketingpanel.auth;

import com.marketingpanel.common.ApiException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** Access to the current caller. Every tenant query takes its workspace id from here, never from the request body. */
public final class Auth {
    private Auth() {}

    public static AuthUser user() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a == null || !(a.getPrincipal() instanceof AuthUser u)) throw ApiException.unauthorized("Please log in.");
        return u;
    }

    public static boolean present() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a != null && a.getPrincipal() instanceof AuthUser;
    }

    public static long userId() {
        return user().userId();
    }

    /** Workspace id of the caller (the JWT "ws" claim). */
    public static long ws() {
        Long ws = user().workspaceId();
        if (ws == null) throw ApiException.forbidden("Choose a workspace first.");
        return ws;
    }
}
