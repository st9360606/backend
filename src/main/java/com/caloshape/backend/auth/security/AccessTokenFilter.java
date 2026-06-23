package com.caloshape.backend.auth.security;

import com.caloshape.backend.auth.entity.AuthToken;
import com.caloshape.backend.auth.repo.AuthTokenRepo;
import jakarta.servlet.DispatcherType;
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

    // OncePerRequestFilter 層級：避免這個 Filter 參與 async / error redispatch。
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return true;
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return true;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();

        // Request 層級防呆：即使 filter chain 在特殊 dispatch 階段仍被觸發，也不要驗 Bearer token。
        return request.getDispatcherType() == DispatcherType.ERROR
                || request.getDispatcherType() == DispatcherType.ASYNC
                || request.getDispatcherType() == DispatcherType.FORWARD
                || "OPTIONS".equalsIgnoreCase(request.getMethod())
                || "/error".equals(path)
                || path.startsWith("/auth")
                || path.startsWith("/actuator")
                || path.startsWith("/internal")
                || path.startsWith("/static/weight-photos/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest req,
            HttpServletResponse res,
            FilterChain chain
    ) throws ServletException, IOException {

        String auth = req.getHeader(HttpHeaders.AUTHORIZATION);

        if (auth == null || auth.isBlank()) {
            chain.doFilter(req, res);
            return;
        }

        if (!auth.startsWith("Bearer ")) {
            unauthorized(res, "Invalid authorization header");
            return;
        }

        String raw = auth.substring("Bearer ".length()).trim();

        if (raw.isBlank()) {
            unauthorized(res, "Bearer token is blank");
            return;
        }

        Optional<AuthToken> found = tokens.findByToken(raw);
        if (found.isEmpty()) {
            unauthorized(res, "Invalid or expired access token");
            return;
        }

        AuthToken at = found.get();

        boolean active = !at.isRevoked()
                && at.getExpiresAt() != null
                && at.getExpiresAt().isAfter(Instant.now());

        if (!active) {
            unauthorized(res, "Invalid or expired access token");
            return;
        }

        Long uid = at.getUserId();
        if (uid == null && at.getUser() != null) {
            uid = at.getUser().getId();
        }

        if (uid == null) {
            unauthorized(res, "Invalid access token user");
            return;
        }

        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                uid,
                null,
                Collections.emptyList()
        );
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(req));

        SecurityContextHolder.getContext().setAuthentication(authentication);
        req.setAttribute("userId", uid);

        chain.doFilter(req, res);
    }

    private static void unauthorized(HttpServletResponse res, String message) throws IOException {
        SecurityContextHolder.clearContext();

        if (res.isCommitted()) {
            return;
        }

        res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        res.setContentType("application/json;charset=UTF-8");
        res.getWriter().write("{\"code\":\"UNAUTHORIZED\",\"message\":\"" + escapeJson(message) + "\"}");
    }

    private static String escapeJson(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }
}
