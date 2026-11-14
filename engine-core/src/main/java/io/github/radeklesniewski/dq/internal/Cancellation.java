package io.github.radeklesniewski.dq.internal;

import io.github.radeklesniewski.dq.RunCancelledException;

/**
 * A run's cancellation points. A run is cancelled by interrupting its calling thread; the resulting
 * {@link RunCancelledException} carries the totals so far. The thread's interrupt flag is left set for the caller to
 * see. Used only from the run's calling thread. Internal.
 */
final class Cancellation {

    private final SummaryCounter summary;

    Cancellation(SummaryCounter summary) {
        this.summary = summary;
    }

    /**
     * Cancellation point, checked between batches.
     *
     * @throws RunCancelledException if the calling thread has been interrupted
     */
    void throwIfRequested() {
        if (Thread.currentThread().isInterrupted()) {
            throw cancelled(null);
        }
    }

    /**
     * Translates an interruption that surfaced while waiting. Restores the interrupt flag, which catching
     * {@link InterruptedException} cleared.
     *
     * @return the exception to throw
     */
    RunCancelledException interrupted(InterruptedException e) {
        Thread.currentThread().interrupt();
        return cancelled(e);
    }

    private RunCancelledException cancelled(Throwable cause) {
        return new RunCancelledException(summary.snapshot(), cause);
    }
}
