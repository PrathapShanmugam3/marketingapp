package com.marketingpanel.auth;

import java.io.Serializable;

/** Authenticated principal built from the access token. */
public record AuthUser(long userId, Long workspaceId, String role, String adminRole, String accountType, Long sessionId)
        implements Serializable {
    public boolean isAdmin() {
        return adminRole != null && !"NONE".equals(adminRole);
    }

    public boolean isStaffRole() {
        return "STAFF".equals(role);
    }

    public boolean isOwnerLevel() {
        return "OWNER".equals(role) || "AGENCY_ADMIN".equals(role);
    }

    public boolean canManage() {
        return isOwnerLevel() || "MANAGER".equals(role);
    }
}
