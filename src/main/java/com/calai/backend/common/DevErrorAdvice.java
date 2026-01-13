package com.calai.backend.common;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@Profile({"dev","test"})
@RestControllerAdvice
public class DevErrorAdvice {

    @ExceptionHandler(Throwable.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Map<String, Object> any(Throwable ex) {
        Throwable root = rootCause(ex);
        return Map.of(
                "type", ex.getClass().getName(),
                "message", String.valueOf(ex.getMessage()),
                "rootType", root.getClass().getName(),
                "rootMessage", String.valueOf(root.getMessage()),
                "stackTop", firstFrames(root, 5)
        );
    }

    private static Throwable rootCause(Throwable t) {
        Throwable cur = t, next;
        while ((next = cur.getCause()) != null) cur = next;
        return cur;
    }

    private static List<String> firstFrames(Throwable t, int n) {
        StackTraceElement[] arr = t.getStackTrace();
        List<String> out = new ArrayList<>();
        for (int i = 0; i < Math.min(n, arr.length); i++) out.add(arr[i].toString());
        return out;
    }
}
