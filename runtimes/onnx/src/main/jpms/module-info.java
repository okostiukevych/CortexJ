module io.cortexj.runtime.onnx {
    requires transitive io.cortexj.api;
    requires transitive io.cortexj.model;
    requires com.microsoft.onnxruntime;
    exports io.cortexj.runtime.onnx;
    provides io.cortexj.spi.runtime.RuntimeProvider
            with io.cortexj.runtime.onnx.OnnxRuntimeProvider;
}
