/**
 * Generic Llama-family decoder (QWEN2/QWEN3/LLAMA presets) as
 * pure-Java DJL blocks: frozen base weights as plain NDArrays,
 * LoRA A/B as the only trainable child blocks, HF safetensors
 * weight loading (ADR-021/022).
 */
package io.cortexj.train.djl.models;
