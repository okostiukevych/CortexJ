package io.cortexj.tensor;

/**
 * Memory layout. Only row-major dense storage is supported in 0.x;
 * the layout is explicit in the contract so future layouts fail
 * typed instead of silently reinterpreting data.
 */
public enum Layout {
    ROW_MAJOR
}
