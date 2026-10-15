package io.cortexj.train.djl.models;

import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.training.ParameterStore;

/**
 * One projection call: when a LoRA wrapper owns the module, the frozen
 * weight lives inside the wrapper (single application); otherwise the
 * frozen NDArray is applied directly.
 */
final class Projections {

    static NDArray project(NDArray x, NDArray frozenWeight, NDArray frozenBias,
                            LoraLinear lora, ParameterStore store, boolean training) {
        if (lora != null) {
            return lora.forward(store, new NDList(x), training).singletonOrThrow();
        }
        NDArray out = x.matMul(frozenWeight);
        return frozenBias == null ? out : out.add(frozenBias);
    }

    private Projections() {
    }
}
