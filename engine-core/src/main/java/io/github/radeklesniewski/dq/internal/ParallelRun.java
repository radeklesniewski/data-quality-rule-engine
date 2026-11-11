package io.github.radeklesniewski.dq.internal;

import io.github.radeklesniewski.dq.Outcome;
import io.github.radeklesniewski.dq.RunSummary;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@code PARALLEL_ORDERED} / {@code PARALLEL_UNORDERED} execution (ADR-0006). Internal.
 *
 * <p>The calling thread reads batches and hands each to a worker pool. A bounded window of in-flight batches gives
 * backpressure: when it is full, the reader stops and emits a finished batch first. Workers only compute outcome
 * lists. All emission happens on the calling thread, so the sink is never called concurrently.
 *
 * <ul>
 *   <li>Ordered: batches are emitted in submission order. A slow batch holds back later ones (head-of-line blocking).</li>
 *   <li>Unordered: batches are emitted in completion order. Within a batch, order is unchanged.</li>
 * </ul>
 *
 * <p>Working memory: up to {@code maxInFlightBatches} batches of records and their outcomes.
 */
final class ParallelRun {

    private static final AtomicLong RUN_IDS = new AtomicLong();

    private final BatchReader reader;
    private final RecordEvaluator evaluator;
    private final Emitter emitter;
    private final int parallelism;
    private final int maxInFlight;
    private final boolean ordered;

    ParallelRun(BatchReader reader, RecordEvaluator evaluator, Emitter emitter, int parallelism, int maxInFlight,
            boolean ordered) {
        this.reader = reader;
        this.evaluator = evaluator;
        this.emitter = emitter;
        this.parallelism = parallelism;
        this.maxInFlight = maxInFlight;
        this.ordered = ordered;
    }

    RunSummary execute() {
        ExecutorService pool = Executors.newFixedThreadPool(parallelism, workerThreads());
        boolean completed = false;
        try {
            Window window = ordered ? new OrderedWindow(pool) : new CompletionWindow(pool);
            while (true) {
                emitter.throwIfCancelled();
                List<BatchItem> batch;
                try {
                    batch = reader.next();
                } catch (RuntimeException sourceFailure) {
                    // Like sequential mode: deliver everything read before the source broke, then fail.
                    while (window.size() > 0) {
                        emit(window.takeNext());
                    }
                    throw sourceFailure;
                }
                if (batch.isEmpty()) {
                    break;
                }
                while (window.size() >= maxInFlight) {
                    emit(window.takeNext());
                }
                window.submit(() -> evaluator.evaluateBatch(batch));
                for (List<Outcome> ready = window.pollReady(); ready != null; ready = window.pollReady()) {
                    emit(ready);
                }
            }
            while (window.size() > 0) {
                emit(window.takeNext());
            }
            completed = true;
            return emitter.summary();
        } finally {
            if (!completed) {
                pool.shutdownNow();
            }
            pool.close(); // waits for workers to finish, so no thread outlives the run
        }
    }

    private void emit(List<Outcome> outcomes) {
        outcomes.forEach(emitter);
        emitter.batchCompleted();
    }

    /** Waits for a future on the calling thread, translating interruption into cancellation. */
    private List<Outcome> await(Future<List<Outcome>> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw emitter.cancelled(e);
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

    /** The in-flight batches and the policy for which one is emitted next. */
    private interface Window {
        void submit(Callable<List<Outcome>> task);

        int size();

        /** Blocks until the next batch to emit is done. */
        List<Outcome> takeNext();

        /** Returns the next batch to emit if it's already done, else {@code null}. */
        List<Outcome> pollReady();
    }

    /** Emits in submission order. */
    private final class OrderedWindow implements Window {
        private final ExecutorService pool;
        private final Deque<Future<List<Outcome>>> futures = new ArrayDeque<>();

        OrderedWindow(ExecutorService pool) {
            this.pool = pool;
        }

        @Override
        public void submit(Callable<List<Outcome>> task) {
            futures.addLast(pool.submit(task));
        }

        @Override
        public int size() {
            return futures.size();
        }

        @Override
        public List<Outcome> takeNext() {
            return await(futures.removeFirst());
        }

        @Override
        public List<Outcome> pollReady() {
            Future<List<Outcome>> head = futures.peekFirst();
            return head != null && head.isDone() ? await(futures.removeFirst()) : null;
        }
    }

    /** Emits in completion order. */
    private final class CompletionWindow implements Window {
        private final CompletionService<List<Outcome>> completions;
        private int pending;

        CompletionWindow(ExecutorService pool) {
            this.completions = new ExecutorCompletionService<>(pool);
        }

        @Override
        public void submit(Callable<List<Outcome>> task) {
            completions.submit(task);
            pending++;
        }

        @Override
        public int size() {
            return pending;
        }

        @Override
        public List<Outcome> takeNext() {
            Future<List<Outcome>> done;
            try {
                done = completions.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw emitter.cancelled(e);
            }
            pending--;
            return await(done);
        }

        @Override
        public List<Outcome> pollReady() {
            Future<List<Outcome>> done = completions.poll();
            if (done == null) {
                return null;
            }
            pending--;
            return await(done);
        }
    }
}
