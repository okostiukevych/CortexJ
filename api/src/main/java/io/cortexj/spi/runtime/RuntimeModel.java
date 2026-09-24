package io.cortexj.spi.runtime;

import io.cortexj.error.InferenceException;
import io.cortexj.inference.InferenceRequest;
import io.cortexj.inference.InferenceResponse;
import io.cortexj.model.ModelCapabilities;

import java.util.concurrent.CompletionStage;

public interface RuntimeModel extends AutoCloseable {

    ModelCapabilities capabilities();

    InferenceResponse execute(InferenceRequest request);

    CompletionStage<InferenceResponse> executeAsync(InferenceRequest request);

    @Override
    void close();

    static InferenceResponse executeSync(RuntimeModel model, InferenceRequest request) throws InferenceException {
        return model.execute(request);
    }
}
