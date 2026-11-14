package io.github.radeklesniewski.dq.internal;

/**
 * The parts every execution strategy works with, wired once per run by {@link Runs}. All of them share the run's
 * {@link SummaryCounter}. Internal.
 *
 * @param reader pulls batches from the source
 * @param evaluator turns batch items into outcomes
 * @param emitter delivers and counts outcomes
 * @param cancellation the run's cancellation points
 */
record RunContext(BatchReader reader, RecordEvaluator evaluator, Emitter emitter, Cancellation cancellation) {
}
