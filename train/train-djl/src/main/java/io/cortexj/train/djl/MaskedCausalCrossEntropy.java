package io.cortexj.train.djl;

import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.training.loss.Loss;

/**
 * Masked causal cross-entropy: labels NDList carries (labels int64
 * [N,T], mask float [N,T]); the loss is the CE of the next-token
 * logits at positions with mask 1 (padding and prompt positions are
 * excluded), averaged over valid positions.
 */
final class MaskedCausalCrossEntropy extends Loss {

    MaskedCausalCrossEntropy() {
        super("cortexj-masked-ce");
    }

    @Override
    public NDArray evaluate(NDList labels, NDList predictions) {
        NDArray label = labels.get(0);
        NDArray mask = labels.get(1);
        NDArray logits = predictions.singletonOrThrow();
        long[] shape = logits.getShape().getShape();
        long rows = shape[0] * shape[1];
        long vocab = shape[2];
        NDArray logProbs = logits.reshape(rows, vocab).logSoftmax(1);
        NDArray nll = logProbs.gather(label.reshape(rows, 1), 1).reshape(rows);
        NDArray flatMask = mask.reshape(rows);
        return nll.mul(flatMask).sum().div(flatMask.sum()).neg();
    }
}
