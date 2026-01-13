package com.calai.backend.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String ATTR = "requestId";
    public static final String MDC_KEY = "rid";

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {

        String rid = req.getHeader(HEADER);
        if (rid == null || rid.isBlank()) rid = UUID.randomUUID().toString();

        // 讓後續 controller / advice 都拿得到
        req.setAttribute(ATTR, rid);

        // 讓 log pattern 可印出 rid（可選）
        MDC.put(MDC_KEY, rid);

        // ✅ 成功/失敗都會帶回去
        res.setHeader(HEADER, rid);

        try {
            chain.doFilter(req, res);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    public static String getOrCreate(HttpServletRequest req) {
        Object v = req.getAttribute(ATTR);
        return (v == null) ? UUID.randomUUID().toString() : String.valueOf(v);
    }
}
