package com.calai.backend.auth.security;

import com.calai.backend.auth.entity.AuthToken;
import com.calai.backend.auth.repo.AuthTokenRepo;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.Collections;
import java.util.Optional;

@Component
public class AccessTokenFilter extends OncePerRequestFilter {

    private final AuthTokenRepo tokens;

    public AccessTokenFilter(AuthTokenRepo tokens) {
        this.tokens = tokens;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String p = request.getRequestURI();
        // 放行登入與健康檢查
        return p.startsWith("/auth") || p.startsWith("/actuator");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {

        String auth = req.getHeader(HttpHeaders.AUTHORIZATION);
        if (auth == null || !auth.startsWith("Bearer ")) {
            chain.doFilter(req, res); // 交給後面處理（匿名或由 EntryPoint 回 401）
            return;
        }

        String raw = auth.substring(7).trim();

        Optional<AuthToken> found = tokens.findByToken(raw);
        if (found.isEmpty()) {
            unauthorized(res);
            return;
        }

        AuthToken at = found.get();

        // 驗證是否有效
        boolean active = !at.isRevoked()
                && at.getExpiresAt() != null
                && at.getExpiresAt().isAfter(Instant.now());

        if (!active) {
            unauthorized(res);
            return;
        }

        // **重點**：只拿 userId，避免將 JPA Entity 放入 SecurityContext
        Long uid = at.getUserId();
        if (uid == null && at.getUser() != null) {
            // 退而求其次：從關聯拿 id（讀 id 不會觸發 toString）
            uid = at.getUser().getId();
        }

        if (uid == null) {
            unauthorized(res);
            return;
        }

        var authentication = new UsernamePasswordAuthenticationToken(
                uid, // principal 只放 userId（Long）
                null,
                Collections.emptyList()
        );
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(req));
        SecurityContextHolder.getContext().setAuthentication(authentication);

        // 相容：部分程式仍從 request attribute 取 userId
        req.setAttribute("userId", uid);

        chain.doFilter(req, res);
    }

    private static void unauthorized(HttpServletResponse res) throws IOException {
        res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        res.setContentType("application/json");
        res.getWriter().write("{\"code\":\"UNAUTHORIZED\",\"message\":\"Invalid or expired access token\"}");
    }
}
