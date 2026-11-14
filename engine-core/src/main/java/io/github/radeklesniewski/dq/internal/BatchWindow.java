package io.github.radeklesniewski.dq.internal;

import io.github.radeklesniewski.dq.ExecutionMode;
import io.github.radeklesniewski.dq.Outcome;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * The in-flight batches of a parallel run, and the policy for which one is emitted next (ADR-0006). A window only
 * hands out futures: waiting on them, and translating interruption and failures, is {@link ParallelRun}'s job. Used
 * only from the run's calling thread. Internal.
 */
sealed interface BatchWindow permits BatchWindow.SubmissionOrder, BatchWindow.CompletionOrder {

    /** The window for a parallel {@code mode}, submitting batches to {@code pool}. */
    static BatchWindow of(ExecutionMode mode, ExecutorService pool) {
        return switch (mode) {
            case PARALLEL_ORDERED -> new SubmissionOrder(pool);
            case PARALLEL_UNORDERED -> new CompletionOrder(pool);
            case SEQUENTIAL -> throw new IllegalArgumentException("A sequential run has no batch window");
        };
    }

    /** Starts evaluating one batch. */
    void submit(Callable<List<Outcome>> batch);

    /** The number of batches submitted but not yet handed out. */
    int size();

    default boolean isEmpty() {
        return size() == 0;
    }

    /**
     * Hands out the next batch to emit. Its future may still be running; the caller waits on it.
     *
     * @throws InterruptedException if interrupted while waiting for a batch to finish (completion order only)
     */
    Future<List<Outcome>> takeNext() throws InterruptedException;

    /** Hands out the next batch to emit only if it has finished already. */
    Optional<Future<List<Outcome>>> pollReady();

    /** {@code PARALLEL_ORDERED}: batches are emitted in submission order, so a slow batch holds back later ones. */
    final class SubmissionOrder implements BatchWindow {
        private final ExecutorService pool;
        private final Deque<Future<List<Outcome>>> futures = new ArrayDeque<>();

        SubmissionOrder(ExecutorService pool) {
            this.pool = pool;
        }

        @Override
        public void submit(Callable<List<Outcome>> batch) {
            futures.addLast(pool.submit(batch));
        }

        @Override
        public int size() {
            return futures.size();
        }

        @Override
        public Future<List<Outcome>> takeNext() {
            return futures.removeFirst();
        }

        @Override
        public Optional<Future<List<Outcome>>> pollReady() {
            Future<List<Outcome>> head = futures.peekFirst();
            return head != null && head.isDone() ? Optional.of(futures.removeFirst()) : Optional.empty();
        }
    }

    /** {@code PARALLEL_UNORDERED}: batches are emitted in completion order; order within a batch is unchanged. */
    final class CompletionOrder implements BatchWindow {
        private final CompletionService<List<Outcome>> completions;
        private int pending;

        CompletionOrder(ExecutorService pool) {
            this.completions = new ExecutorCompletionService<>(pool);
        }

        @Override
        public void submit(Callable<List<Outcome>> batch) {
            completions.submit(batch);
            pending++;
        }

        @Override
        public int size() {
            return pending;
        }

        @Override
        public Future<List<Outcome>> takeNext() throws InterruptedException {
            Future<List<Outcome>> done = completions.take();
            pending--;
            return done;
        }

        @Override
        public Optional<Future<List<Outcome>>> pollReady() {
            Optional<Future<List<Outcome>>> done = Optional.ofNullable(completions.poll());
            done.ifPresent(future -> pending--);
            return done;
        }
    }
}
