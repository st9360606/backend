package com.calai.backend.auth.utils;

import java.security.SecureRandom;

public final class SecureToken {
    private static final SecureRandom SR = new SecureRandom();

    public static String newTokenHex(int bytes) {
        byte[] buf = new byte[bytes]; // 32 bytes → 256-bit
        SR.nextBytes(buf);
        StringBuilder sb = new StringBuilder(bytes * 2);
        for (byte b : buf) sb.append(String.format("%02x", b));
        return sb.toString(); // 長度 64 的 hex 字串
    }
}