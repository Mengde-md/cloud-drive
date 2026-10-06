package com.mengde.clouddrive.ai;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.*;

final class McpBoundedBody implements HttpResponse.BodySubscriber<byte[]> {
    private final CompletableFuture<byte[]> body = new CompletableFuture<>();
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private Flow.Subscription subscription;
    @Override public CompletionStage<byte[]> getBody() { return body; }
    @Override public synchronized void onSubscribe(Flow.Subscription value) {
        if (subscription != null) { value.cancel(); return; }
        subscription = value;
        if (body.isDone()) value.cancel(); else value.request(1);
    }
    @Override public synchronized void onNext(List<ByteBuffer> buffers) {
        if (body.isDone()) return;
        for (ByteBuffer buffer : buffers) {
            if (buffer.remaining() > 16000 - bytes.size()) { fail(new AiFailure(502, "MCP_BACKEND_OUTPUT_INVALID")); return; }
            byte[] chunk = new byte[buffer.remaining()]; buffer.get(chunk); bytes.writeBytes(chunk);
        }
        subscription.request(1);
    }
    @Override public synchronized void onError(Throwable failure) { fail(failure); }
    @Override public synchronized void onComplete() { body.complete(bytes.toByteArray()); }
    synchronized void fail(Throwable failure) {
        body.completeExceptionally(failure);
        if (subscription != null) subscription.cancel();
    }
}
