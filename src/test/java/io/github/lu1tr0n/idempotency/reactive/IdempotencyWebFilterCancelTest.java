package io.github.lu1tr0n.idempotency.reactive;

import io.github.lu1tr0n.idempotency.autoconfigure.IdempotencyProperties;
import io.github.lu1tr0n.idempotency.heartbeat.LockHeartbeat;
import io.github.lu1tr0n.idempotency.store.InMemoryIdempotencyStore;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;

import reactor.core.Disposable;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reactive cancel path: when a client disconnects mid-handler, the request's
 * {@code doOnSuccess}/{@code doOnError} never fire — only {@code doFinally} does.
 * Without the {@code doFinally(stop)} a heartbeat would renew the abandoned lock
 * forever, so this pins that the cancel signal stops renewal.
 */
class IdempotencyWebFilterCancelTest {

    @Test
    void cancelStopsTheHeartbeat() throws InterruptedException {
        InMemoryIdempotencyStore store = new InMemoryIdempotencyStore();
        IdempotencyProperties properties = new IdempotencyProperties();

        AtomicInteger starts = new AtomicInteger();
        AtomicInteger stops = new AtomicInteger();
        LockHeartbeat spy = (key, token) -> {
            starts.incrementAndGet();
            return stops::incrementAndGet;
        };

        IdempotencyWebFilter filter = new IdempotencyWebFilter(store, properties, null, spy);

        MockServerHttpRequest request = MockServerHttpRequest.post("/pay")
            .header(properties.getHeaderName(), "cancel-1")
            .contentType(MediaType.APPLICATION_JSON)
            .body("{}");
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        // Handler that never completes — the request is in-flight, lock held.
        WebFilterChain hangingChain = ex -> Mono.never();

        Disposable subscription = filter.filter(exchange, hangingChain).subscribe();
        Thread.sleep(300); // let the chain assemble: body read, acquire, heartbeat start
        assertThat(starts.get()).as("heartbeat started for the in-flight request").isEqualTo(1);

        subscription.dispose(); // client disconnect → cancel → doFinally
        Thread.sleep(150);
        assertThat(stops.get()).as("cancel stopped the heartbeat (doFinally)").isEqualTo(1);
    }
}
