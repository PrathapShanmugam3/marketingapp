package com.marketingpanel.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.marketingpanel.common.Db;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
public class JwtAuthFilter extends OncePerRequestFilter {
    private final JwtService jwt;
    private final Db db;
    private final ObjectMapper om;
    private final Cache<Long, String> wsStatus = Caffeine.newBuilder().expireAfterWrite(30, TimeUnit.SECONDS).maximumSize(50_000).build();

    public JwtAuthFilter(JwtService jwt, Db db, ObjectMapper om) {
        this.jwt = jwt;
        this.db = db;
        this.om = om;
    }

    public void evict(long workspaceId) {
        wsStatus.invalidate(workspaceId);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String h = req.getHeader("Authorization");
        if (h != null && h.startsWith("Bearer ")) {
            try {
                AuthUser u = jwt.parse(h.substring(7));
                List<GrantedAuthority> auth = new ArrayList<>();
                if (u.role() != null) auth.add(new SimpleGrantedAuthority("ROLE_" + u.role()));
                if (u.isAdmin()) auth.add(new SimpleGrantedAuthority("ROLE_" + u.adminRole()));
                if ("PROMOTER".equals(u.accountType()) || "BOTH".equals(u.accountType()))
                    auth.add(new SimpleGrantedAuthority("ROLE_PROMOTER"));
                if (u.workspaceId() != null && !"GET".equals(req.getMethod()) && blocked(u.workspaceId(), req.getRequestURI())) {
                    res.setStatus(403);
                    res.setContentType("application/json;charset=UTF-8");
                    om.writeValue(res.getOutputStream(), Map.of("status", 403, "code", "FORBIDDEN",
                            "message", "This workspace is suspended. Contact support.", "fieldErrors", List.of()));
                    return;
                }
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(u, null, auth));
            } catch (Exception e) {
                SecurityContextHolder.clearContext(); // invalid/expired token -> 401 from the entry point
            }
        }
        try {
            chain.doFilter(req, res);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private boolean blocked(long ws, String path) {
        if (path.startsWith("/api/v1/support") || path.startsWith("/api/v1/auth")) return false;
        String st = wsStatus.get(ws, id -> db.scalar("select status from workspaces where id=:id and deleted_at is null", String.class, "id", id));
        return "SUSPENDED".equals(st) || "CANCELLED".equals(st);
    }
}
