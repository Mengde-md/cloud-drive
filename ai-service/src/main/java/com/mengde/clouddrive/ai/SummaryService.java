package com.mengde.clouddrive.ai;

import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.chat.response.StreamingHandle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.BiFunction;

public final class SummaryService implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(SummaryService.class);
    private final AiSettings settings;
    private final DeepSeekClient client;
    private final SummaryValidator validator = new SummaryValidator();
    private final Semaphore slots;
    private final ExecutorService workers;
    private final ScheduledThreadPoolExecutor clock = new ScheduledThreadPoolExecutor(1);

    public SummaryService(AiSettings settings, DeepSeekClient client) {
        this.settings = settings;
        this.client = client;
        slots = new Semaphore(settings.maxConcurrentCalls());
        clock.setRemoveOnCancelPolicy(true);
        workers = new ThreadPoolExecutor(settings.maxConcurrentCalls(), settings.maxConcurrentCalls(),
                0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(settings.maxConcurrentCalls()),
                new ThreadPoolExecutor.AbortPolicy());
    }

    private void acquire() {
        if (!settings.enabled()) throw new AiFailure(503, "AI_DISABLED");
        if (!slots.tryAcquire()) throw new AiFailure(429, "AI_BUSY");
    }

    public Result summarize(long ownerId, String text) {
        return execute(ownerId, () -> client.summarize(text),
                (id, response) -> result(id, ownerId, response));
    }

    public Generated answer(long ownerId, String evidenceJson) {
        return execute(ownerId, () -> client.answer(evidenceJson), (id, response) ->
                new Generated(id, settings.model(), response, usage(id, ownerId, response)));
    }

    private <T> T execute(long ownerId, Callable<ChatResponse> request,
                          BiFunction<String, ChatResponse, T> completion) {
        acquire();
        Future<ChatResponse> future = null;
        String id = UUID.randomUUID().toString();
        try {
            future = workers.submit(request);
            return completion.apply(id, future.get(settings.timeoutMillis(), TimeUnit.MILLISECONDS));
        } catch (TimeoutException failure) {
            log.info("event=ai_failure requestId={} ownerId={} code=AI_TIMEOUT cost=unknown", id, ownerId);
            throw new AiFailure(504, "AI_TIMEOUT");
        }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AiFailure(503, "AI_INTERRUPTED"); }
        catch (ExecutionException failure) { throw new AiFailure(502, "AI_UPSTREAM_FAILED"); }
        catch (RejectedExecutionException failure) { throw new AiFailure(429, "AI_BUSY"); }
        finally {
            if (future != null && !future.isDone()) future.cancel(true);
            slots.release();
        }
    }

    private Result result(String id, long ownerId, ChatResponse response) {
        AiCost cost = usage(id, ownerId, response);
        return new Result(id, settings.model(), validator.validate(response), cost);
    }

    private AiCost usage(String id, long ownerId, ChatResponse response) {
        AiCost cost = AiCost.from(response == null ? null : response.tokenUsage(), settings.protocolFixture());
        log.info("event=ai_usage requestId={} ownerId={} inputTokens={} outputTokens={} estimatedUsd={} fixture={}",
                id, ownerId, cost.inputTokens(), cost.outputTokens(), cost.estimatedUsd(), cost.protocolFixture());
        return cost;
    }

    public SseEmitter stream(long ownerId, String text) {
        acquire();
        SseEmitter emitter = new SseEmitter((long) settings.timeoutMillis() + 1000);
        StreamCall call = new StreamCall(emitter, ownerId);
        emitter.onCompletion(call::cancel);
        emitter.onError(failure -> call.cancel());
        emitter.onTimeout(() -> call.fail("AI_TIMEOUT"));
        call.deadline = clock.schedule(() -> call.fail("AI_TIMEOUT"), settings.timeoutMillis(), TimeUnit.MILLISECONDS);
        try {
            workers.execute(() -> call.start(text));
        } catch (RejectedExecutionException failure) { call.fail("AI_BUSY"); }
        return emitter;
    }

    private final class StreamCall implements StreamingChatResponseHandler {
        private final SseEmitter emitter;
        private final long owner;
        private final String id = UUID.randomUUID().toString();
        private boolean closed;
        private int received;
        private StreamingHandle handle;
        private ScheduledFuture<?> deadline;
        private StreamCall(SseEmitter emitter, long owner) { this.emitter = emitter; this.owner = owner; }

        private void start(String text) {
            synchronized (this) { if (closed) return; }
            try { client.stream(text, this); }
            catch (RuntimeException failure) { fail("AI_UPSTREAM_FAILED"); }
        }

        @Override public synchronized void onPartialResponse(PartialResponse partial, PartialResponseContext context) {
            handle = context.streamingHandle();
            if (closed) { handle.cancel(); return; }
            received += partial.text().length();
            if (received > SummaryValidator.MAX_RESPONSE_CHARS) { fail("AI_OUTPUT_INVALID"); return; }
            send("progress", Map.of("requestId", id, "receivedChars", received, "validated", false));
        }

        @Override public synchronized void onCompleteResponse(ChatResponse response) {
            if (closed) return;
            try {
                Result accepted = result(id, owner, response);
                send("result", accepted);
                cancel();
                emitter.complete();
            } catch (AiFailure failure) { fail(failure.getMessage()); }
            catch (RuntimeException failure) { fail("AI_UPSTREAM_FAILED"); }
        }

        @Override public void onError(Throwable failure) { fail("AI_UPSTREAM_FAILED"); }

        private synchronized void send(String name, Object body) {
            if (closed) return;
            try { emitter.send(SseEmitter.event().name(name).data(body)); }
            catch (IOException | IllegalStateException failure) { cancel(); }
        }

        private synchronized void fail(String code) {
            if (closed) return;
            if (code.equals("AI_TIMEOUT") || code.equals("AI_UPSTREAM_FAILED") || code.equals("AI_USAGE_INVALID")) {
                log.info("event=ai_failure requestId={} ownerId={} code={} cost=unknown", id, owner, code);
            }
            send("error", Map.of("requestId", id, "code", code));
            cancel();
            emitter.complete();
        }

        private synchronized void cancel() {
            if (closed) return;
            closed = true;
            if (deadline != null) deadline.cancel(false);
            try { if (handle != null) handle.cancel(); }
            finally { slots.release(); }
        }
    }

    @Override public void close() { workers.shutdownNow(); clock.shutdownNow(); }
    public record Result(String requestId, String model, SummaryValidator.Summary output, AiCost usage) { }
    public record Generated(String requestId, String model, ChatResponse response, AiCost usage) { }
}
