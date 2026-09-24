package io.cortexj.stream;

public interface TokenStream extends AutoCloseable {

    StreamSubscription subscribe(TokenConsumer consumer);

    @Override
    void close();
}
