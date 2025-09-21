package com.calai.backend.auth.security;

import com.calai.backend.auth.entity.User;
import com.calai.backend.auth.service.TokenService;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class AccessTokenFilter extends OncePerRequestFilter {

    private final TokenService tokens;

    public AccessTokenFilter(TokenService tokens) { this.tokens = tokens; }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String auth = req.getHeader(HttpHeaders.AUTHORIZATION);
        if (auth != null && auth.startsWith("Bearer ")) {
            String token = auth.substring(7);
            User u = tokens.validateAccess(token);
            if (u == null) {
                res.setStatus(401);
                return;
            }
            // 這裡簡化：把 userId 放到 request attribute；若你已用 Spring Security，可設 SecurityContext
            req.setAttribute("userId", u.getId());
        }
        chain.doFilter(req, res);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String p = request.getRequestURI();
        return p.startsWith("/auth/") || p.startsWith("/actuator");
    }
}
