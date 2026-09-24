package io.cortexj.core;

import io.cortexj.ErrorCode;
import io.cortexj.StarterInfo;
import io.cortexj.error.ConfigurationException;
import io.cortexj.spi.platform.PlatformModule;

import java.util.List;

public final class PlatformModuleSelector {

    private PlatformModuleSelector() {
    }

    public static PlatformModule select(EnvironmentSnapshot snapshot) {
        validateStarters(snapshot);
        int currentFeature = snapshot.environment.jvmFeatureVersion();
        List<PlatformModule> modules = SpiRegistries.resilientLoad(PlatformModule.class);
        PlatformModule best = null;
        for (PlatformModule module : modules) {
            if (module.featureVersion() <= currentFeature) {
                if (best == null || module.featureVersion() > best.featureVersion()) {
                    best = module;
                }
            }
        }
        if (best == null) {
            best = PlatformModule.forCurrentVmFallback();
        }
        return best;
    }

    public static void validateStarters(EnvironmentSnapshot snapshot) {
        int currentFeature = snapshot.environment.jvmFeatureVersion();
        for (StarterInfo starter : SpiRegistries.resilientLoad(StarterInfo.class)) {
            if (starter.requiredFeatureVersion() > currentFeature) {
                ConfigurationException failure = new ConfigurationException(
                        ErrorCode.CONF_STARTER_JVM_MISMATCH,
                        "Starter '" + starter.starterId() + "' requires Java "
                                + starter.requiredFeatureVersion()
                                + ", but this JVM is Java " + currentFeature
                                + " (" + snapshot.environment.javaVersion() + ").",
                        "Replace '" + starter.starterId() + "' with the starter matching your JVM, "
                                + "for example 'cortexj-starter-java" + currentFeature + "'.");
                failure.detail("starter", starter.starterId());
                failure.detail("requiredJava", String.valueOf(starter.requiredFeatureVersion()));
                failure.detail("currentJava", String.valueOf(currentFeature));
                throw failure;
            }
        }
    }
}
