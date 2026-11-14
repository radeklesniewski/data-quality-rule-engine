package io.github.radeklesniewski.dq.internal;

import io.github.radeklesniewski.dq.ExecutionMode;
import io.github.radeklesniewski.dq.Outcome;
import io.github.radeklesniewski.dq.RunOptions;
import io.github.radeklesniewski.dq.RunSummary;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@code PARALLEL_ORDERED} / {@code PARALLEL_UNORDERED} execution (ADR-0006). Internal.
 *
 * <p>The calling thread reads batches and hands each to a worker pool. A bounded {@link BatchWindow} of in-flight
 * batches gives backpressure: when it is full, the reader stops and emits a finished batch first. Workers only compute
 * outcome lists. All emission happens on the calling thread, so the sink is never called concurrently. Which batch is
 * emitted next (submission or completion order) is the window's policy.
 *
 * <p>Working memory: up to {@code maxInFlightBatches} batches of records and their outcomes, plus the one being read.
 */
final class ParallelRun {

    private static final AtomicLong RUN_IDS = new AtomicLong();

    private final BatchReader reader;
    private final RecordEvaluator evaluator;
    private final Emitter emitter;
    private final Cancellation cancellation;
    private final ExecutionMode mode;
    private final int parallelism;
    private final int maxInFlight;

    ParallelRun(RunContext run, RunOptions options) {
        this.reader = run.reader();
        this.evaluator = run.evaluator();
        this.emitter = run.emitter();
        this.cancellation = run.cancellation();
        this.mode = options.executionMode();
        this.parallelism = options.parallelism();
        this.maxInFlight = options.maxInFlightBatches();
    }

    RunSummary execute() {
        ExecutorService pool = Executors.newFixedThreadPool(parallelism, workerThreads());
        boolean completed = false;
        try {
            BatchWindow window = BatchWindow.of(mode, pool);
            while (true) {
                cancellation.throwIfRequested();
                List<BatchItem> batch = nextBatch(window);
                if (batch.isEmpty()) {
                    break;
                }
                awaitFreeSlot(window);
                window.submit(() -> evaluator.evaluateBatch(batch));
                emitReady(window);
            }
            drain(window);
            completed = true;
            return emitter.summary();
        } finally {
            if (!completed) {
                pool.shutdownNow();
            }
            pool.close(); // waits for workers to finish, so no thread outlives the run
        }
    }

    /** Reads the next batch. If the source breaks, everything read before it is delivered first, as sequentially. */
    private List<BatchItem> nextBatch(BatchWindow window) {
        try {
            return reader.next();
        } catch (RuntimeException sourceFailure) {
            drain(window);
            throw sourceFailure;
        }
    }

    /** Backpressure: emits batches, waiting for them if needed, until there is room for one more. */
    private void awaitFreeSlot(BatchWindow window) {
        while (window.size() >= maxInFlight) {
            emit(await(takeNext(window)));
        }
    }

    /** Emits every batch that is already finished and next in line, without waiting. */
    private void emitReady(BatchWindow window) {
        Optional<Future<List<Outcome>>> ready = window.pollReady();
        while (ready.isPresent()) {
            emit(await(ready.get()));
            ready = window.pollReady();
        }
    }

    /** Emits every batch still in flight, waiting for each. */
    private void drain(BatchWindow window) {
        while (!window.isEmpty()) {
            emit(await(takeNext(window)));
        }
    }

    private void emit(List<Outcome> outcomes) {
        outcomes.forEach(emitter);
        emitter.batchCompleted();
    }

    private Future<List<Outcome>> takeNext(BatchWindow window) {
        try {
            return window.takeNext();
        } catch (InterruptedException e) {
            throw cancellation.interrupted(e);
        }
    }

    /** Waits for a batch on the calling thread, translating interruption into cancellation. */
    private List<Outcome> await(Future<List<Outcome>> batch) {
        try {
            return batch.get();
        } catch (InterruptedException e) {
            throw cancellation.interrupted(e);
        } catch (ExecutionException e) {
            // Isolated failures never get here: only fatal errors (e.g. OutOfMemoryError) or engine bugs do.
            switch (e.getCause()) {
                case RuntimeException runtime -> throw runtime;
                case Error error -> throw error;
                default -> throw new IllegalStateException("Batch evaluation failed", e.getCause());
            }
        }
    }

    private static ThreadFactory workerThreads() {
        long runId = RUN_IDS.incrementAndGet();
        AtomicInteger workerIds = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, "dq-engine-run" + runId + "-worker" + workerIds.incrementAndGet());
            thread.setDaemon(true); // a rule that never returns must not keep the JVM alive
            return thread;
        };
    }
}
