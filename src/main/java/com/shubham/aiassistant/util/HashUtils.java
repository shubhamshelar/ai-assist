package com.shubham.aiassistant.util;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Cryptographic utility helpers. */
public final class HashUtils {

    private HashUtils() {}

    /**
     * Computes the SHA-256 hex digest of the given bytes.
     *
     * @throws IllegalStateException if the SHA-256 algorithm is unavailable (never in practice)
     */
    public static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}
