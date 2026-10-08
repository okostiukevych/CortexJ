package io.cortexj.data;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * Dataset identity: SHA-256 over schema plus canonical cells
 * (null renders as {@code <null>}, cells joined by {@code \0}).
 * Same data in any adapter yields the same fingerprint.
 */
public final class Fingerprint {

    private Fingerprint() {
    }

    public static String ofFrame(Frame frame) {
        StringBuilder canonical = new StringBuilder();
        List<String> names = frame.schema().names();
        List<ColType> types = frame.schema().types();
        for (int i = 0; i < names.size(); i++) {
            canonical.append(names.get(i)).append(':').append(types.get(i)).append('\n');
        }
        for (List<String> row : frame.rows()) {
            for (int i = 0; i < row.size(); i++) {
                if (i > 0) {
                    canonical.append('\0');
                }
                String cell = row.get(i);
                canonical.append(cell == null ? "<null>" : cell);
            }
            canonical.append('\n');
        }
        return sha256Hex(canonical.toString());
    }

    public static String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required", e);
        }
    }
}
