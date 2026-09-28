package io.cortexj.cache;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class Checksums {

    private Checksums() {
    }

    public static String sha256Hex(Path file) throws IOException {
        MessageDigest digest = digest("SHA-256");
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        return toHex(digest.digest());
    }

    public static String sha256Hex(byte[] bytes) {
        MessageDigest digest = digest("SHA-256");
        return toHex(digest.digest(bytes));
    }

    /**
     * Verifies the file against the expected hex digest.
     *
     * <p>Behavior when no checksum is declared ({@code expectedHex == null} or
     * blank): returns {@code true} <b>by design</b> - the file is treated as
     * <em>unverified</em>, not as trusted. Repository providers must attach a
     * real checksum to {@link io.cortexj.model.ModelArtifact} whenever the
     * repository publishes one (Hugging Face LFS sha256 via {@code ?blobs=true});
     * providers that cannot know one mark the artifact metadata
     * {@code integrity=unverified} so the gap is visible, not silent.</p>
     */
    public static boolean verify(Path file, String algorithm, String expectedHex) throws IOException {
        if (expectedHex == null || expectedHex.trim().isEmpty()) {
            return true;
        }
        String actual = hexOfFile(file, algorithm);
        return expectedHex.trim().toLowerCase().equals(actual);
    }

    private static String hexOfFile(Path file, String algorithm) throws IOException {
        MessageDigest digest = digest(algorithm);
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        return toHex(digest.digest());
    }

    private static MessageDigest digest(String algorithm) {
        String canonical = canonicalAlgorithm(algorithm);
        try {
            return MessageDigest.getInstance(canonical);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Missing digest algorithm " + algorithm, e);
        }
    }

    private static String canonicalAlgorithm(String algorithm) {
        if (algorithm == null) {
            return "SHA-256";
        }
        String normalized = algorithm.trim().toLowerCase(java.util.Locale.ROOT);
        if (normalized.equals("sha256") || normalized.equals("sha-256")) {
            return "SHA-256";
        }
        if (normalized.equals("sha1") || normalized.equals("sha-1")) {
            return "SHA-1";
        }
        if (normalized.equals("md5")) {
            return "MD5";
        }
        return algorithm.trim();
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
