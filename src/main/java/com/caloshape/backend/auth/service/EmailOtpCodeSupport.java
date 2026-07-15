package com.caloshape.backend.auth.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

final class EmailOtpCodeSupport {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private EmailOtpCodeSupport() {
    }

    static String generateNumeric(int length) {
        if (length < 6) {
            throw new IllegalArgumentException("OTP length must be at least 6 digits");
        }

        StringBuilder code = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            code.append((char) ('0' + SECURE_RANDOM.nextInt(10)));
        }
        return code.toString();
    }

    static String sha256(String value) {
        if (value == null) {
            throw new IllegalArgumentException("OTP must not be null");
        }

        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    static boolean matchesHash(String expectedHash, String submittedCode) {
        if (expectedHash == null || submittedCode == null) {
            return false;
        }

        byte[] expected = expectedHash.getBytes(StandardCharsets.US_ASCII);
        byte[] actual = sha256(submittedCode).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, actual);
    }
}
