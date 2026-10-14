package com.marketingpanel.auth;

import com.marketingpanel.config.Settings;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/** JWT access tokens: 15 minutes, kept in memory by the browser (SRS 4.6). */
@Component
public class JwtService {
    public static final long ACCESS_TTL_SECONDS = 15 * 60;
    private final SecretKey key;

    public JwtService(Settings s) {
        byte[] k = s.jwtSecret.getBytes(StandardCharsets.UTF_8);
        if (k.length < 32) throw new IllegalStateException("app.security.jwt-secret must be at least 32 bytes");
        this.key = Keys.hmacShaKeyFor(k);
    }

    public String issue(AuthUser u) {
        long now = System.currentTimeMillis();
        var b = Jwts.builder().subject(String.valueOf(u.userId()))
                .claim("adm", u.adminRole() == null ? "NONE" : u.adminRole())
                .claim("at", u.accountType())
                .issuedAt(new Date(now))
                .expiration(new Date(now + ACCESS_TTL_SECONDS * 1000));
        if (u.workspaceId() != null) b.claim("ws", u.workspaceId());
        if (u.role() != null) b.claim("role", u.role());
        if (u.sessionId() != null) b.claim("sid", u.sessionId());
        return b.signWith(key).compact();
    }

    public AuthUser parse(String token) {
        Claims c = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
        Number ws = c.get("ws", Number.class);
        Number sid = c.get("sid", Number.class);
        return new AuthUser(Long.parseLong(c.getSubject()), ws == null ? null : ws.longValue(), c.get("role", String.class),
                c.get("adm", String.class), c.get("at", String.class), sid == null ? null : sid.longValue());
    }
}
