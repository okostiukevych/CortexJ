package io.cortexj.hardware.internal;

import io.cortexj.hardware.EnvironmentProfile;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;

public final class EnvironmentProbe {

    public EnvironmentProfile probe() {
        String vendor = safeProperty("java.vendor", "unknown");
        String jvmName = safeProperty("java.vm.name", "unknown");
        String javaVersion = safeProperty("java.version", "unknown");
        String os = safeProperty("os.name", "unknown");
        String arch = safeProperty("os.arch", "unknown");
        int featureVersion = parseFeatureVersion(javaVersion, safeProperty("java.specification.version", javaVersion));
        int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
        long maxHeap = Runtime.getRuntime().maxMemory();

        long totalPhysical = totalPhysicalMemoryBytes();
        long available = availablePhysicalMemoryBytes();
        boolean nativeLikely = detectNativeLikelihood(os, featureVersion);

        return new EnvironmentProfile(
                vendor, jvmName, featureVersion, javaVersion, os, arch,
                cores, maxHeap, totalPhysical, available, nativeLikely, null);
    }

    private static String safeProperty(String key, String fallback) {
        try {
            String value = System.getProperty(key);
            return value == null ? fallback : value;
        } catch (SecurityException e) {
            return fallback;
        }
    }

    public static int parseFeatureVersion(String javaVersion, String specVersion) {
        String source = specVersion != null && !specVersion.isEmpty() ? specVersion : javaVersion;
        if (source == null) {
            return 8;
        }
        if (source.startsWith("1.")) {
            try {
                return Integer.parseInt(source.substring(2, Math.min(source.length(), 3)));
            } catch (NumberFormatException ignored) {
                return 8;
            }
        }
        String digits = leadingDigits(source);
        if (digits.isEmpty()) {
            return 8;
        }
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return 8;
        }
    }

    private static String leadingDigits(String value) {
        StringBuilder sb = new StringBuilder();
        for (char c : value.toCharArray()) {
            if (Character.isDigit(c)) {
                sb.append(c);
            } else {
                break;
            }
        }
        return sb.toString();
    }

    private static long totalPhysicalMemoryBytes() {
        Long value = osBeanValue("getTotalPhysicalMemorySize");
        return value == null ? 0L : value.longValue();
    }

    private static long availablePhysicalMemoryBytes() {
        Long committed = osBeanValue("getCommittedVirtualMemorySize");
        Long total = osBeanValue("getTotalPhysicalMemorySize");
        Long free = osBeanValue("getFreePhysicalMemorySize");
        if (total != null && free != null) {
            long estimate = total.longValue() - free.longValue();
            if (committed != null && committed.longValue() > estimate) {
                estimate = committed.longValue();
            }
            return Math.max(0L, total.longValue() - estimate);
        }
        return 0L;
    }

    private static Long osBeanValue(String methodName) {
        try {
            Object bean = ManagementFactory.getOperatingSystemMXBean();
            Class<?> type = Class.forName("com.sun.management.OperatingSystemMXBean");
            if (!type.isInstance(bean)) {
                return null;
            }
            Method method = type.getMethod(methodName);
            Object result = method.invoke(bean);
            return result instanceof Number ? Long.valueOf(((Number) result).longValue()) : null;
        } catch (Throwable e) {
            return null;
        }
    }

    private static boolean detectNativeLikelihood(String os, int featureVersion) {
        String lower = os.toLowerCase();
        return lower.contains("linux") || lower.contains("mac") || lower.contains("darwin") || lower.contains("windows");
    }
}
