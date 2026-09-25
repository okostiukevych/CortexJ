# ONNX Runtime Guide

`cortexj-runtime-onnx` executes ONNX embedding models locally through the bundled
ONNX Runtime (CPU execution provider), with a real Hugging Face tokenizer.

## What the provider declares

| Property | Value |
|---|---|
| id | `onnx` |
| formats | ONNX |
| devices | CPU (honest: the bundled runtime ships the CPU EP only) |
| tasks | EMBEDDING |
| streaming | not supported |

The planner only picks this runtime when the resolved model offers an ONNX artifact and
the task is embedding. GGUF-only models are rejected with `FORMAT_UNSUPPORTED` reasons.

## Tokenizer

`io.cortexj.model.token.HfTokenizer` (shared by ONNX and DJL providers) reads
`tokenizer.json` shipped next to the model:

- WordPiece (BERT-style) with CLS/SEP wrapping;
- BPE (GPT-style byte-level merges);
- Unigram;
- whitespace fallback when `tokenizer.json` is missing or unreadable.

## Usage

```java
try (ModelHandle model = platform.models().load(
        ModelReference.of("sentence-transformers/all-MiniLM-L6-v2"))) {
    EmbeddingCapability embedder = model.capability(EmbeddingCapability.class);
    EmbeddingResponse response = embedder.embed(
            EmbeddingRequest.texts(List.of("hello", "world")));
    float[] vector = response.vectors().get(0);
}
```

Batch embeddings are required by spec §45 and fully supported.

## Requirements

- ONNX model exported with an `input_ids` (or equivalently named) int64 input.
- Mean pooling is applied over the token dimension automatically.
- Pull the model first (`cortexj pull <model>`) or work online; cache rules apply.

## Honest limitations

- CPU only — GPU EP builds are a packaging concern (spec §36) and not bundled.
- Text generation through ONNX is not declared; use llama.cpp or a remote runtime.
