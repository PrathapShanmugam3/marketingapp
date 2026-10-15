package com.marketingpanel.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketingpanel.auth.JwtAuthFilter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public SecurityFilterChain chain(HttpSecurity http, JwtAuthFilter jwt, ObjectMapper om) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .cors(c -> {})
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(h -> h
                        .httpStrictTransportSecurity(x -> x.includeSubDomains(true).maxAgeInSeconds(31536000))
                        .frameOptions(f -> f.disable())
                        .referrerPolicy(r -> r.policy(org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN)))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/api/v1/auth/**", "/api/v1/earn/signup", "/api/v1/public/**").permitAll()
                        .requestMatchers("/f/**", "/r/**", "/l/**", "/o/**", "/pb", "/p/**", "/s/**", "/u/**", "/b/**", "/c/**", "/files/**").permitAll()
                        .requestMatchers("/webhooks/**", "/internal/**", "/dev/**", "/ws/**", "/ws").permitAll()
                        .requestMatchers("/api/v1/integrations/**").permitAll()
                        .requestMatchers("/actuator/health/**", "/actuator/health", "/actuator/info").permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> write(om, res, 401, "UNAUTHORIZED", "Please log in."))
                        .accessDeniedHandler((req, res, ex) -> write(om, res, 403, "FORBIDDEN", "You do not have access to this.")))
                .addFilterBefore(jwt, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    static void write(ObjectMapper om, HttpServletResponse res, int status, String code, String msg) throws java.io.IOException {
        res.setStatus(status);
        res.setContentType("application/json;charset=UTF-8");
        om.writeValue(res.getOutputStream(), Map.of("status", status, "code", code, "message", msg, "fieldErrors", List.of()));
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource(Settings s) {
        CorsConfiguration c = new CorsConfiguration();
        c.setAllowedOrigins(s.corsOrigins);
        c.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        c.setAllowedHeaders(List.of("*"));
        c.setExposedHeaders(List.of("Retry-After", "X-Trace-Id"));
        c.setAllowCredentials(true);
        c.setMaxAge(Duration.ofHours(1));
        UrlBasedCorsConfigurationSource src = new UrlBasedCorsConfigurationSource();
        src.registerCorsConfiguration("/api/**", c);
        return src;
    }
}
