package io.cortexj.diagnostics;

import io.cortexj.model.ModelReference;
import io.cortexj.planner.ExecutionRequest;

public interface Support {

    SupportReport inspect(ModelReference reference);

    SupportReport inspect(ExecutionRequest request);
}
