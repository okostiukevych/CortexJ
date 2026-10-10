// Versioned C ABI shim over libtorch (ADR-023, issue #181).
// Built from source by the CI lane (clang, no cmake needed):
//
//   clang++ -std=c++17 -shared -fPIC -O2 -Wno-invalid-specialization \
//     -o libtorchshim.dylib torch_shim.cpp \
//     -I$LIBTORCH/include -I$LIBTORCH/include/torch/csrc/api/include \
//     -L$LIBTORCH/lib -ltorch -ltorch_cpu -lc10
//
// Apple clang needs -Wno-invalid-specialization for the libtorch
// headers (verified live 2026-10-10). Checked-in binaries are
// forbidden; the lane rebuilds per OS/arch.
#include <cmath>
#include <cstdint>
#include <torch/torch.h>

// Pinned ABI version. The Java side refuses any other version typed.
#define SPIKE_ABI_VERSION 1

extern "C" {

int32_t spike_abi_version() {
    return SPIKE_ABI_VERSION;
}

const char *spike_libtorch_version() {
    return TORCH_VERSION;
}

void *spike_tensor_create(const float *values, int64_t rows, int64_t cols) {
    try {
        torch::TensorOptions options = torch::TensorOptions().dtype(torch::kFloat32);
        torch::Tensor owned = torch::zeros({rows, cols}, options);
        float *data = owned.data_ptr<float>();
        for (int64_t i = 0; i < rows * cols; i++) {
            data[i] = values[i];
        }
        return new torch::Tensor(std::move(owned));
    } catch (...) {
        return nullptr;
    }
}

static void *binaryOp(const void *left, const void *right,
                      torch::Tensor (*op)(const torch::Tensor &, const torch::Tensor &)) {
    try {
        const torch::Tensor *a = static_cast<const torch::Tensor *>(left);
        const torch::Tensor *b = static_cast<const torch::Tensor *>(right);
        return new torch::Tensor(op(*a, *b));
    } catch (...) {
        return nullptr;
    }
}

// ATen does not export operators as plain function pointers portably,
// so each op gets its own thin wrapper below.
static torch::Tensor addTensors(const torch::Tensor &a, const torch::Tensor &b) {
    return a + b;
}

static torch::Tensor mulTensors(const torch::Tensor &a, const torch::Tensor &b) {
    return a * b;
}

static torch::Tensor matmulTensors(const torch::Tensor &a, const torch::Tensor &b) {
    return a.matmul(b);
}

void *spike_tensor_add(const void *left, const void *right) {
    return binaryOp(left, right, addTensors);
}

void *spike_tensor_mul(const void *left, const void *right) {
    return binaryOp(left, right, mulTensors);
}

void *spike_tensor_matmul(const void *left, const void *right) {
    return binaryOp(left, right, matmulTensors);
}

void *spike_tensor_relu(const void *handle) {
    try {
        const torch::Tensor *tensor = static_cast<const torch::Tensor *>(handle);
        return new torch::Tensor(torch::relu(*tensor));
    } catch (...) {
        return nullptr;
    }
}

// Copies up to capacity floats into out; returns the element count,
// negative on error or truncation.
int64_t spike_tensor_read(const void *handle, float *out, int64_t capacity) {
    try {
        const torch::Tensor *tensor = static_cast<const torch::Tensor *>(handle);
        torch::Tensor flat = tensor->reshape(-1).to(torch::kCPU).contiguous();
        int64_t count = flat.numel();
        if (count > capacity) {
            return -count;
        }
        const float *data = flat.data_ptr<float>();
        for (int64_t i = 0; i < count; i++) {
            out[i] = data[i];
        }
        return count;
    } catch (...) {
        return -1;
    }
}

void spike_tensor_destroy(void *handle) {
    delete static_cast<torch::Tensor *>(handle);
}

} // extern "C"
