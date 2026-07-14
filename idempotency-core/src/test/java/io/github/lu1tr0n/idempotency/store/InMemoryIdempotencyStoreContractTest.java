package io.github.lu1tr0n.idempotency.store;

import io.github.lu1tr0n.idempotency.core.AbstractIdempotencyStoreContractTest;
import io.github.lu1tr0n.idempotency.core.IdempotencyStore;

/**
 * Runs the shipped {@link AbstractIdempotencyStoreContractTest} against the
 * in-memory reference implementation. This is also the worked example a backend
 * author copies: extend the TCK, return your store from {@link #newStore()}.
 */
class InMemoryIdempotencyStoreContractTest extends AbstractIdempotencyStoreContractTest {

    @Override
    protected IdempotencyStore newStore() {
        return new InMemoryIdempotencyStore();
    }
}
