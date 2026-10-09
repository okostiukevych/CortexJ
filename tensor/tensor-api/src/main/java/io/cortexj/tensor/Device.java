package io.cortexj.tensor;

/**
 * Compute device. Concrete accelerator metadata stays in the backends;
 {@code AUTO} means the backend chooses.
 */
public enum Device {
    CPU,
    GPU,
    AUTO
}
