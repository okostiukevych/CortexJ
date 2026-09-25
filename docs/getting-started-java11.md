# Getting Started — Java 11

On Java 11 use `cortexj-starter-java11`. Everything from the
[Java 8 guide](getting-started-java8.md) applies; additionally the `streaming/flow`
adapter module (Java `Flow.Publisher` bridging) is part of the JDK 11+ reactor.

## 1. Add the starter

```xml
<dependency>
    <groupId>io.cortexj</groupId>
    <artifactId>cortexj-starter-java11</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

## 2. Use the Java Flow streaming adapter

```java
import io.cortexj.stream.Token;
import io.cortexj.stream.TokenStream;
import io.cortexj.stream.flow.TokenFlowAdapter;

import java.util.concurrent.Flow;

TokenStream stream = generator.stream(GenerationRequest.prompt("hi"));
Flow.Publisher<Token> publisher = TokenFlowAdapter.publisher(stream);

publisher.subscribe(new Flow.Subscriber<Token>() {
    private Flow.Subscription subscription;

    @Override
    public void onSubscribe(Flow.Subscription s) {
        this.subscription = s;
        s.request(1);
    }

    @Override
    public void onNext(Token token) {
        System.out.print(token.text());
        subscription.request(1);
    }

    @Override
    public void onComplete() {
        System.out.println();
    }

    @Override
    public void onError(Throwable error) {
        error.printStackTrace();
    }
});
```

The adapter respects `Flow` demand: tokens emitted while no demand is outstanding are
buffered and drained when `request(n)` arrives; `onComplete` fires only after the buffer
is fully drained.

## 3. Verification

The full shared core is built and tested on a real JDK 11 (see README "Build"). The
starter validates the JVM at boot and refuses mismatches with an actionable error.
