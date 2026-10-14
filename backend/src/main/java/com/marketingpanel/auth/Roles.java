package com.marketingpanel.auth;

/** SpEL expressions for @PreAuthorize, following the permission matrix in SRS 2.3. */
public final class Roles {
    public static final String MEMBER = "hasAnyRole('OWNER','MANAGER','STAFF','AGENCY_ADMIN')";
    public static final String MANAGER = "hasAnyRole('OWNER','MANAGER','AGENCY_ADMIN')";
    public static final String OWNER = "hasAnyRole('OWNER','AGENCY_ADMIN')";
    public static final String AGENCY = "hasRole('AGENCY_ADMIN')";
    public static final String PROMOTER = "hasRole('PROMOTER')";
    public static final String FINANCE = "hasAnyRole('FINANCE','SUPER_ADMIN')";
    public static final String SUPER = "hasRole('SUPER_ADMIN')";
    public static final String ADMIN = "hasAnyRole('SUPER_ADMIN','FINANCE','SUPPORT')";
    public static final String MANAGER_OR_SUPER = "hasAnyRole('OWNER','MANAGER','AGENCY_ADMIN','SUPER_ADMIN')";
    public static final String AUTHENTICATED = "isAuthenticated()";

    private Roles() {}
}
