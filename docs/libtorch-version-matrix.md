# LibTorch version matrix (ADR-023, condition 1)

The shim pins ONE libtorch release line per CortexJ release. The
Java side refuses any other shim ABI typed (`spike_abi_version`).

| CortexJ | libtorch | shim ABI | Status |
|---|---|---|---|
| 0.1.0-SNAPSHOT | 2.7.1 (macOS arm64) | 1 | live-verified 2026-10-10 |

Build the shim from source (CI lane does this per OS/arch; checked-in
binaries are forbidden):

```bash
clang++ -std=c++17 -shared -fPIC -O2 -Wno-invalid-specialization \
  -o libtorchshim.dylib tensor/tensor-libtorch/src/main/native/torch_shim.cpp \
  -I$LIBTORCH/include -I$LIBTORCH/include/torch/csrc/api/include \
  -L$LIBTORCH/lib -ltorch -ltorch_cpu -lc10
```

Toolchain notes (verified live):

- Apple clang needs `-Wno-invalid-specialization` for the libtorch
  headers.
- PyTorch 2.7.1 references a Homebrew libomp absolute path from its
  build machine; point it at the bundled `libomp.dylib`
  (`install_name_tool -change ... @rpath/libomp.dylib`) or ship the
  Homebrew path.
- Run with `--enable-native-access=ALL-UNNAMED` and the shim path in
  `libtorch.shimPath` (config) or `CORTEXJ_LIBTORCH_SHIM` (env).
