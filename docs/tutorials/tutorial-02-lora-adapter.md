# Tutorial 2 — first LoRA adapter (30 minutes)

You declare a LoRA config, merge it into base weights, and round-trip
it through the standard safetensors container. Every snippet runs
against `cortexj-train-peft` (see `LoRAAdapterTest`).

## 1. Declare the config

```java
LoRAConfig config = new LoRAConfig(2, 4, 0.0, List.of("q_proj"));
// rank 2, alpha 4 (scaling 2.0), no dropout, one target module
```

## 2. Hold the matrices

```java
float[][] a = {{0.5f, -0.25f}, {1.0f, 0.0f}};          // rank x input
float[][] b = {{1.0f, 0.0f}, {0.25f, 0.5f}, {0.0f, 2.0f}}; // output x rank
LoRAAdapter adapter = new LoRAAdapter("q_proj", 2, 3, config, a, b);
```

## 3. Merge into base weights

```java
float[][] merged = adapter.merge(baseWeights); // W' = W + (alpha/rank)·B·A
```

The base matrix stays untouched; shapes mismatch fails typed.

## 4. Ship it as safetensors

```java
LoRASafetensors.write(adapter, Path.of("adapter_model.safetensors"),
        Map.of("note", "my first adapter"));
LoRAAdapter read = LoRASafetensors.read(Path.of("adapter_model.safetensors"));
```

The container is standard safetensors (`adapter.A`/`adapter.B`,
`__metadata__` sidecar) — readable by any compliant parser. Foreign
adapter naming fails typed with a clear message, never silently
(`LoRASafetensorsInteropTest`).

## 5. Deploy the flow

The live fine-tuning path (DJL PyTorch engine → trained delta →
this container → checksum → provenance) is exercised by
`DjlLiveTrainingTest` (skips loudly without the engine).
