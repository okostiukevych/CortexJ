package io.cortexj.runtime.llamacpp;

interface LlamaServerEndpoint {

    String baseUrl();

    void stop();
}
