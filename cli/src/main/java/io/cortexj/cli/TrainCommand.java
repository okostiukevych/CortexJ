package io.cortexj.cli;

import io.cortexj.train.LmDataset;
import io.cortexj.train.LoraSettings;
import io.cortexj.train.TrainingJobs;
import io.cortexj.train.TrainingRequest;
import io.cortexj.train.TrainingResult;
import io.cortexj.train.TrainingStrategy;
import io.cortexj.train.data.LmDatasets;
import io.cortexj.train.djl.DjlTrainingBackend;
import io.cortexj.train.djl.models.DjlHfTokenizer;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** cortexj train: thin CLI over the TrainingJobs API path (ADR-021). */
public final class TrainCommand {

    public static void run(String[] args) throws Exception {
        Map<String, String> parsed = parse(args);
        String modelPath = parsed.get("modelPath");
        String datasetFile = parsed.get("dataset");
        if (datasetFile == null) {
            throw new IllegalArgumentException("--dataset is required");
        }
        String format = parsed.getOrDefault("format", "sft").toLowerCase(Locale.ROOT);
        int rank = Integer.parseInt(parsed.getOrDefault("rank", "8"));
        int alpha = Integer.parseInt(parsed.getOrDefault("alpha", "16"));
        int epochs = Integer.parseInt(parsed.getOrDefault("epochs", "1"));
        double lr = Double.parseDouble(parsed.getOrDefault("lr", "2e-4"));
        int seqLen = Integer.parseInt(parsed.getOrDefault("seqLen", "512"));
        DjlHfTokenizer tokenizer = DjlHfTokenizer.load(
                java.nio.file.Paths.get(modelPath, "tokenizer.json"));
        LmDataset dataset;
        if ("text".equals(format)) {
            dataset = LmDatasets.jsonlText(java.nio.file.Paths.get(datasetFile), tokenizer, seqLen);
        } else if ("chat".equals(format)) {
            String templateId = parsed.get("chatTemplate");
            if (templateId == null || templateId.trim().isEmpty()) {
                templateId = defaultTemplate(
                        io.cortexj.train.djl.models.ArchitectureConfigs.parse(
                                java.nio.file.Paths.get(modelPath, "config.json")).family());
            }
            dataset = LmDatasets.jsonlChat(java.nio.file.Paths.get(datasetFile), tokenizer,
                    seqLen, templateId);
        } else {
            dataset = LmDatasets.jsonlSft(java.nio.file.Paths.get(datasetFile), tokenizer, seqLen);
        }
        TrainingRequest.Builder builder = TrainingRequest.builder()
                .model(modelPath)
                .dataset(dataset.fingerprint())
                .strategy(TrainingStrategy.LORA)
                .lora(LoraSettings.of(rank, alpha, Arrays.asList(
                        parsed.getOrDefault("targets", "q_proj,k_proj,v_proj,o_proj").split(","))))
                .lmData(dataset)
                .epochs(epochs)
                .learningRate(lr)
                .parameter("modelPath", modelPath)
                .parameter("seqLen", String.valueOf(seqLen));
        if (parsed.containsKey("out")) {
            builder.parameter("outputDir", parsed.get("out"));
        }
        TrainingResult result = new TrainingJobs(
                Arrays.asList(new DjlTrainingBackend())).start(builder.build());
        System.out.println("job: " + result.jobId());
        System.out.println("adapter: " + result.artifactPath());
        for (Map.Entry<String, Double> metric : result.metrics().entrySet()) {
            System.out.println(metric.getKey() + ": " + metric.getValue());
        }
    }

    /** Family-derived chat template default: Qwen -> ChatML, Llama -> llama3. */
    static String defaultTemplate(io.cortexj.train.djl.models.DecoderFamily family) {
        switch (family) {
            case QWEN2:
            case QWEN3:
                return "qwen";
            case LLAMA:
                return "llama3";
            default:
                throw new IllegalArgumentException("unsupported family: " + family);
        }
    }

    static Map<String, String> parse(String[] args) {
        if (args.length < 2) {
            throw new IllegalArgumentException(
                    "usage: cortexj train <modelPath> --dataset <file> [--format sft|text|chat]"
                            + " [--chatTemplate qwen|llama3] [--rank N] [--alpha N] [--targets a,b]"
                            + " [--epochs N] [--lr F] [--seqLen N] [--out dir]");
        }
        Map<String, String> parsed = new HashMap<String, String>();
        parsed.put("modelPath", args[1]);
        for (int i = 2; i + 1 < args.length; i += 2) {
            if (!args[i].startsWith("--")) {
                throw new IllegalArgumentException("expected --flag at " + args[i]);
            }
            parsed.put(args[i].substring(2), args[i + 1]);
        }
        return parsed;
    }

    private TrainCommand() {
    }
}
