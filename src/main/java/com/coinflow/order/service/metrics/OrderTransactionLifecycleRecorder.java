package com.coinflow.order.service.metrics;

import com.coinflow.order.domain.OrderSide;
import org.springframework.stereotype.Component;

@Component
public class OrderTransactionLifecycleRecorder {

    private final OrderCreateStageRecorder stageRecorder;

    public OrderTransactionLifecycleRecorder(OrderCreateStageRecorder stageRecorder) {
        this.stageRecorder = stageRecorder;
    }

    public Metrics start(String marketSymbol, OrderSide side, long templateStartedAt) {
        return new Metrics(marketSymbol, side, templateStartedAt);
    }

    public class Metrics {
        private final String marketSymbol;
        private final OrderSide side;
        private final long templateStartedAt;

        private long callbackStartedAt = -1L;
        private long callbackFinishedAt = -1L;
        private long beforeCommitAt = -1L;
        private long beforeCompletionAt = -1L;
        private long afterCommitStartedAt = -1L;
        private long afterCommitFinishedAt = -1L;
        private long afterCompletionStartedAt = -1L;
        private long afterCompletionFinishedAt = -1L;

        private Metrics(String marketSymbol, OrderSide side, long templateStartedAt) {
            this.marketSymbol = marketSymbol;
            this.side = side;
            this.templateStartedAt = templateStartedAt;
        }

        public void recordCallbackStarted() {
            callbackStartedAt = System.nanoTime();
            stageRecorder.record(marketSymbol, side, "transaction_begin",
                    callbackStartedAt - templateStartedAt);
        }

        public void recordCallbackFinished() {
            callbackFinishedAt = System.nanoTime();
        }

        public void recordBeforeCommit() {
            beforeCommitAt = System.nanoTime();
            if (callbackFinishedAt > 0) {
                stageRecorder.record(marketSymbol, side, "transaction_before_commit_wait",
                        beforeCommitAt - callbackFinishedAt);
            }
        }

        public void recordBeforeCompletion() {
            beforeCompletionAt = System.nanoTime();
            if (beforeCommitAt > 0) {
                stageRecorder.record(marketSymbol, side, "transaction_before_completion",
                        beforeCompletionAt - beforeCommitAt);
            }
        }

        public void recordAfterCommitStarted() {
            afterCommitStartedAt = System.nanoTime();
            if (beforeCompletionAt > 0) {
                stageRecorder.record(marketSymbol, side, "transaction_commit",
                        afterCommitStartedAt - beforeCompletionAt);
            }
        }

        public void recordAfterCommitFinished() {
            afterCommitFinishedAt = System.nanoTime();
            if (afterCommitStartedAt > 0) {
                stageRecorder.record(marketSymbol, side, "transaction_after_commit_callbacks",
                        afterCommitFinishedAt - afterCommitStartedAt);
            }
        }

        public void recordAfterCompletionStarted() {
            afterCompletionStartedAt = System.nanoTime();
            if (afterCommitFinishedAt > 0) {
                stageRecorder.record(marketSymbol, side, "transaction_after_commit_to_completion",
                        afterCompletionStartedAt - afterCommitFinishedAt);
            }
        }

        public void recordAfterCompletionFinished() {
            afterCompletionFinishedAt = System.nanoTime();
            if (afterCompletionStartedAt > 0) {
                stageRecorder.record(marketSymbol, side, "transaction_after_completion_callbacks",
                        afterCompletionFinishedAt - afterCompletionStartedAt);
            }
        }

        public void recordTemplateReturned(long templateFinishedAt) {
            if (callbackFinishedAt > 0) {
                stageRecorder.record(marketSymbol, side, "transaction_after_callback",
                        templateFinishedAt - callbackFinishedAt);
            }
            if (afterCompletionFinishedAt > 0) {
                stageRecorder.record(marketSymbol, side, "transaction_completion_to_return",
                        templateFinishedAt - afterCompletionFinishedAt);
            }
        }
    }
}
