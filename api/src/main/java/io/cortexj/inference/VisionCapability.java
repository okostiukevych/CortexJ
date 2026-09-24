package io.cortexj.inference;

import io.cortexj.model.Modality;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public interface VisionCapability {

    String describe(Modality inputModality, byte[] input, String question);

    static VisionCapability unsupported() {
        return new VisionCapability() {
            @Override
            public String describe(Modality inputModality, byte[] input, String question) {
                throw new UnsupportedOperationException("Vision is not supported by this runtime");
            }
        };
    }
}
