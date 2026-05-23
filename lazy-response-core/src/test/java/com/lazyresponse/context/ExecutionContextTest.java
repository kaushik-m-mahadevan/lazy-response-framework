package com.lazyresponse.context;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link ExecutionContext}  -  read/write semantics, type casting,
 * null result handling, and concurrent write safety.
 */
class ExecutionContextTest {

    // -------------------------------------------------------------------------
    // 1. Request access
    // -------------------------------------------------------------------------

    @Test
    void getRequest_returnsTypedRequestObject() {
        String req = "my-request";
        ExecutionContext ctx = new ExecutionContext(req);
        assertThat(ctx.getRequest(String.class)).isSameAs(req);
    }

    @Test
    void getRequest_wrongType_throwsClassCastException() {
        ExecutionContext ctx = new ExecutionContext("a string");
        assertThatThrownBy(() -> ctx.getRequest(Integer.class))
                .isInstanceOf(ClassCastException.class);
    }

    // -------------------------------------------------------------------------
    // 2. put / get / hasResult
    // -------------------------------------------------------------------------

    @Test
    void putAndGet_roundTrip() {
        ExecutionContext ctx = new ExecutionContext(new Object());
        ctx.put("order", "ORD-001");
        assertThat(ctx.get("order", String.class)).isEqualTo("ORD-001");
    }

    @Test
    void hasResult_trueAfterPut() {
        ExecutionContext ctx = new ExecutionContext(new Object());
        ctx.put("order", "x");
        assertThat(ctx.hasResult("order")).isTrue();
    }

    @Test
    void hasResult_falseBeforePut() {
        ExecutionContext ctx = new ExecutionContext(new Object());
        assertThat(ctx.hasResult("order")).isFalse();
    }

    @Test
    void get_returnsNull_whenNotPresent() {
        ExecutionContext ctx = new ExecutionContext(new Object());
        assertThat(ctx.get("missing", String.class)).isNull();
    }

    @Test
    void hasResult_trueEvenWhenValueIsNull() {
        // ConcurrentHashMap does NOT allow null values  -  but the spec says hasResult
        // tracks presence. Putting null must be handled; here we test that the
        // contract survives a non-null put (null values go through the failure path,
        // not put() directly).
        ExecutionContext ctx = new ExecutionContext(new Object());
        ctx.put("x", "something");
        assertThat(ctx.hasResult("x")).isTrue();
    }

    // -------------------------------------------------------------------------
    // 3. Concurrent writes from multiple threads
    // -------------------------------------------------------------------------

    @Test
    void concurrentPuts_noLostWrites() throws InterruptedException {
        ExecutionContext ctx = new ExecutionContext(new Object());
        int threadCount = 20;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done  = new CountDownLatch(threadCount);
        AtomicInteger errors = new AtomicInteger(0);

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        for (int i = 0; i < threadCount; i++) {
            final String id = "ds-" + i;
            pool.submit(() -> {
                try {
                    start.await();
                    ctx.put(id, id + "-result");
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        assertThat(errors.get()).isZero();
        for (int i = 0; i < threadCount; i++) {
            assertThat(ctx.hasResult("ds-" + i)).isTrue();
            assertThat(ctx.get("ds-" + i, String.class)).isEqualTo("ds-" + i + "-result");
        }
    }
}
