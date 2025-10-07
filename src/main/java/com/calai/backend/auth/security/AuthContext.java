package com.calai.backend.auth.security;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

/**
 * 方案 A：延用你現有 AccessTokenFilter 將 userId 放在 request attribute 的作法。
 * 這裡從 RequestContext 取出 userId。取不到就拋 401。
 */
@Component
public class AuthContext {

    public Long requireUserId() {
        var attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");

        var req = attrs.getRequest();
        Object v = req.getAttribute("userId"); // AccessTokenFilter 放進去的

        if (v instanceof Long l) return l;
        if (v instanceof String s) {
            try { return Long.parseLong(s); } catch (NumberFormatException ignored) {}
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
    }
}
