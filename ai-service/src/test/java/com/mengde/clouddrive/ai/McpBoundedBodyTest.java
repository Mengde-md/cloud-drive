package com.mengde.clouddrive.ai;

import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class McpBoundedBodyTest {
    static class Subscription implements Flow.Subscription {
        int requested, cancelled;
        public void request(long count) { requested += count; }
        public void cancel() { cancelled++; }
    }
    @Test void buffersAreCollectedInOrderWithBoundedDemand() {
        var body = new McpBoundedBody(); var sub = new Subscription(); body.onSubscribe(sub);
        body.onNext(List.of(ByteBuffer.wrap(new byte[]{1, 2}), ByteBuffer.wrap(new byte[]{3})));
        body.onComplete(); assertArrayEquals(new byte[]{1, 2, 3}, body.getBody().toCompletableFuture().join());
        assertEquals(2, sub.requested);
    }
    @Test void exactByteLimitIsAccepted() {
        var body = new McpBoundedBody(); body.onSubscribe(new Subscription());
        body.onNext(List.of(ByteBuffer.wrap(new byte[8000]), ByteBuffer.wrap(new byte[8000])));
        body.onComplete(); assertEquals(16000, body.getBody().toCompletableFuture().join().length);
    }
    @Test void overflowRejectsBeforeConsumingOffendingBufferAndCancels() {
        var body = new McpBoundedBody(); var sub = new Subscription(); body.onSubscribe(sub);
        var large = ByteBuffer.wrap(new byte[16001]); body.onNext(List.of(large));
        var failure = assertThrows(CompletionException.class, () -> body.getBody().toCompletableFuture().join());
        assertEquals("MCP_BACKEND_OUTPUT_INVALID", failure.getCause().getMessage());
        assertEquals(0, large.position()); assertEquals(1, sub.cancelled);
    }
    @Test void rejectionBeforeSubscriptionReadsNothing() {
        var body = new McpBoundedBody(); body.fail(new AiFailure(403, "MCP_BACKEND_UNAUTHORIZED"));
        var sub = new Subscription(); body.onSubscribe(sub); body.onNext(List.of(ByteBuffer.wrap(new byte[]{1})));
        body.onComplete(); assertEquals(0, sub.requested); assertEquals(1, sub.cancelled);
        assertThrows(CompletionException.class, () -> body.getBody().toCompletableFuture().join());
    }
    @Test void duplicateSubscriptionIsCancelledWithoutReplacingOriginal() {
        var body = new McpBoundedBody(); var first = new Subscription(); var second = new Subscription();
        body.onSubscribe(first); body.onSubscribe(second); body.onComplete();
        assertEquals(1, first.requested); assertEquals(1, second.cancelled); assertEquals(0, first.cancelled);
    }
    @Test void streamFailureWinsOverLateCompletion() {
        var body = new McpBoundedBody(); var sub = new Subscription(); body.onSubscribe(sub);
        body.onError(new IllegalStateException("test-stream-failure")); body.onComplete();
        assertEquals("test-stream-failure", assertThrows(CompletionException.class,
                () -> body.getBody().toCompletableFuture().join()).getCause().getMessage());
        assertEquals(1, sub.cancelled);
    }
}
