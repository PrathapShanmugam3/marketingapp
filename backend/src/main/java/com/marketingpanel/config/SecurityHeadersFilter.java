package com.marketingpanel.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** X-Frame-Options DENY and a strict CSP everywhere except public forms/pages, which may be embedded (SRS 4.6). */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class SecurityHeadersFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        String p = req.getRequestURI();
        if (!(p.startsWith("/f/") || p.startsWith("/p/"))) {
            res.setHeader("X-Frame-Options", "DENY");
            res.setHeader("Content-Security-Policy", "default-src 'self'; img-src 'self' data: https:; style-src 'self' 'unsafe-inline'; connect-src 'self' https:; frame-ancestors 'none'");
        }
        chain.doFilter(req, res);
    }
}
