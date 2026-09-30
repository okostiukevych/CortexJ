package io.cortexj;

import io.cortexj.model.ModelHandle;
import io.cortexj.model.ModelReference;
import io.cortexj.planner.ExecutionRequest;

import java.util.concurrent.Future;

public interface Models {

    ModelHandle load(ModelReference reference);

    ModelHandle load(ExecutionRequest request);

    /**
     * Starts the load asynchronously and returns a cancellable future
     * (audit L-4): {@code cancel(true)} aborts an in-flight platform-initiated
     * download promptly - the resume partial is kept per existing semantics -
     * and the future completes exceptionally.
     */
    Future<ModelHandle> loadAsync(ModelReference reference);

    Future<ModelHandle> loadAsync(ExecutionRequest request);
}
