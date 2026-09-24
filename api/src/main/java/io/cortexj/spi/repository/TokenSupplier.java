package io.cortexj.spi.repository;

public interface TokenSupplier {

    String token();

    static TokenSupplier none() {
        return new TokenSupplier() {
            @Override
            public String token() {
                return null;
            }
        };
    }

    static TokenSupplier of(final String token) {
        return new TokenSupplier() {
            @Override
            public String token() {
                return token;
            }
        };
    }
}
