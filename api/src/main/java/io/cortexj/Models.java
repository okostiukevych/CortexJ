package io.cortexj;

import io.cortexj.model.ModelHandle;
import io.cortexj.model.ModelReference;
import io.cortexj.planner.ExecutionRequest;

public interface Models {

    ModelHandle load(ModelReference reference);

    ModelHandle load(ExecutionRequest request);
}
