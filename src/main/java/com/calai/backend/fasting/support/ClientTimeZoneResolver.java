package com.calai.backend.fasting.support;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.ZoneId;

@Component
public class ClientTimeZoneResolver {

    public String resolveFromCurrentRequest() {
        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null) return ZoneId.systemDefault().getId();
        HttpServletRequest req = attrs.getRequest();
        String[] keys = {"X-Client-Timezone", "X-Client-TZ", "Time-Zone", "X-Timezone"};
        for (String k : keys) {
            String v = req.getHeader(k);
            if (v != null && !v.isBlank()) return v.trim();
        }
        return ZoneId.systemDefault().getId();
    }
}

