package com.calai.backend.auth.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

@Component
public class AuthContext {

    public Long requireUserId() {
        // 1) 優先從 SecurityContext 取出我們放的 userId（Long）
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated()) {
            Object p = auth.getPrincipal();
            if (p instanceof Long l) return l;
            if (p instanceof String s) {
                try { return Long.parseLong(s); } catch (NumberFormatException ignored) {}
            }
        }

        // 2) 相容：有些舊程式從 request attribute 取
        var attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs != null) {
            HttpServletRequest req = attrs.getRequest();
            Object v = req.getAttribute("userId");
            if (v instanceof Long l) return l;
            if (v instanceof String s) {
                try { return Long.parseLong(s); } catch (NumberFormatException ignored) {}
            }
        }

        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
    }
}
