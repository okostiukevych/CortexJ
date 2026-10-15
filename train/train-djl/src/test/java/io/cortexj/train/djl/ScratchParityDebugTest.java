package io.cortexj.train.djl;

import io.cortexj.train.djl.models.ArchitectureConfig;
import io.cortexj.train.djl.models.ArchitectureConfigs;
import io.cortexj.train.djl.models.DjlHfTokenizer;
import io.cortexj.train.djl.models.GenericDecoderBlock;
import io.cortexj.train.djl.models.HfWeightLoader;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Collections;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ScratchParityDebugTest {

    @Test
    void dumpTokenizationAndFirstLogits() throws Exception {
        String dirEnv = System.getenv("CORTEXJ_PARITY_MODEL_DIR");
        assumeTrue(dirEnv != null, "needs the model dir");
        assumeTrue(MaskedCausalCrossEntropyTest.enginePresent(), "no engine");
        Path dir = Path.of(dirEnv);
        DjlHfTokenizer tokenizer = DjlHfTokenizer.load(dir.resolve("tokenizer.json"));
        int[] ids = tokenizer.encode("The capital of France is");
        System.out.println("OUR-IDS=" + java.util.Arrays.toString(ids));
        System.out.println("OUR-DECODE-BACK=" + tokenizer.decode(ids));

        ArchitectureConfig config = ArchitectureConfigs.parse(dir.resolve("config.json"));
        GenericDecoderBlock block = GenericDecoderBlock.build(config,
                Collections.<String>emptySet(), 1, 1.0f);
        LmTrainingLoop loop = LmTrainingLoop.create(block, 1e-4f, 1, 0.0f);
        try {
            loop.initialize(512);
            block.loadWeights(HfWeightLoader.load(dir), loop.modelManager());
            ai.djl.ndarray.NDList logits = loop.evalForward(ids);
            float[] last = logits.singletonOrThrow()
                    .get("0, " + (ids.length - 1) + ", :").toFloatArray();
            int best = 0;
            for (int v = 1; v < last.length; v++) {
                if (last[v] > last[best]) {
                    best = v;
                }
            }
            System.out.println("OUR-TOP1=" + best + " logit=" + last[best]);
            System.out.println("OUR-TOP1-DECODED=" + tokenizer.decode(new int[]{best}));
        } finally {
            loop.close();
        }
    }
}
