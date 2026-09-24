package io.cortexj;

public final class ErrorCode {

    public static final String CONF_BOOTSTRAP_MISSING = "CORTEXJ-CONF-0001";
    public static final String CONF_STARTER_JVM_MISMATCH = "CORTEXJ-CONF-0002";
    public static final String CONF_INVALID = "CORTEXJ-CONF-0003";
    public static final String CONF_NO_IO_EXECUTOR = "CORTEXJ-CONF-0004";

    public static final String MODEL_RESOLUTION_FAILED = "CORTEXJ-MODEL-1001";
    public static final String MODEL_NOT_FOUND = "CORTEXJ-MODEL-1002";
    public static final String MODEL_OFFLINE_UNRESOLVED = "CORTEXJ-MODEL-1003";
    public static final String MODEL_REVISION_UNRESOLVED = "CORTEXJ-MODEL-1004";

    public static final String ARTIFACT_RESOLUTION_FAILED = "CORTEXJ-ARTIFACT-2001";
    public static final String ARTIFACT_MISSING = "CORTEXJ-ARTIFACT-2002";
    public static final String ARTIFACT_CHECKSUM_MISMATCH = "CORTEXJ-ARTIFACT-2003";
    public static final String ARTIFACT_TOO_LARGE = "CORTEXJ-ARTIFACT-2004";
    public static final String ARTIFACT_PATH_TRAVERSAL = "CORTEXJ-ARTIFACT-2005";

    public static final String RUNTIME_UNAVAILABLE = "CORTEXJ-RUNTIME-3001";
    public static final String RUNTIME_NOT_INSTALLED = "CORTEXJ-RUNTIME-3002";
    public static final String RUNTIME_MISCONFIGURED = "CORTEXJ-RUNTIME-3003";
    public static final String RUNTIME_JVM_INCOMPATIBLE = "CORTEXJ-RUNTIME-3004";
    public static final String RUNTIME_LOAD_FAILED = "CORTEXJ-RUNTIME-3005";
    public static final String RUNTIME_CAPABILITY_MISSING = "CORTEXJ-RUNTIME-3006";

    public static final String PLAN_NO_CANDIDATE = "CORTEXJ-PLAN-4001";
    public static final String PLAN_USER_REQUIREMENT_IMPOSSIBLE = "CORTEXJ-PLAN-4002";
    public static final String PLAN_MEMORY_LIMIT = "CORTEXJ-PLAN-4003";

    public static final String MEM_INSUFFICIENT = "CORTEXJ-MEM-5001";

    public static final String NATIVE_LIBRARY_MISSING = "CORTEXJ-NATIVE-6001";
    public static final String NATIVE_LOAD_FAILED = "CORTEXJ-NATIVE-6002";

    public static final String CONVERSION_UNSUPPORTED = "CORTEXJ-CONV-7001";
    public static final String CONVERSION_FAILED = "CORTEXJ-CONV-7002";

    public static final String LOAD_FAILED = "CORTEXJ-LOAD-8001";
    public static final String LOAD_LIFECYCLE = "CORTEXJ-LOAD-8002";

    public static final String INFERENCE_FAILED = "CORTEXJ-INFER-9001";
    public static final String INFERENCE_STREAM_FAILED = "CORTEXJ-INFER-9002";
    public static final String INFERENCE_CANCELLED = "CORTEXJ-INFER-9003";
    public static final String INFERENCE_UNSUPPORTED_TASK = "CORTEXJ-INFER-9004";

    public static final String SEC_POLICY_BLOCKED = "CORTEXJ-SEC-10001";
    public static final String SEC_LICENSE_BLOCKED = "CORTEXJ-SEC-10002";

    private ErrorCode() {
    }
}
