package com.coinflow.order.service.command;

import com.coinflow.market.domain.Market;
import com.coinflow.order.domain.OrderSide;
import com.coinflow.order.service.metrics.OrderCreateStageRecorder;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

@Component
public class MarketOrderCommandQueue {

    private final OrderCreateStageRecorder stageRecorder;
    private final MeterRegistry meterRegistry;
    private final ConcurrentMap<Long, MarketWorker> workers = new ConcurrentHashMap<>();

    public MarketOrderCommandQueue(OrderCreateStageRecorder stageRecorder, MeterRegistry meterRegistry) {
        this.stageRecorder = stageRecorder;
        this.meterRegistry = meterRegistry;
    }

    public <T> T submit(Market market, OrderSide side, Supplier<T> command) {
        MarketWorker worker = workers.computeIfAbsent(
                market.getId(),
                ignored -> new MarketWorker(market.getSymbol())
        );
        QueuedCommand<T> queuedCommand = new QueuedCommand<>(market.getSymbol(), side, command);
        worker.submit(queuedCommand);
        return queuedCommand.await();
    }

    public boolean submitAsync(Market market, OrderSide side, Long orderId, Runnable command) {
        MarketWorker worker = workers.computeIfAbsent(
                market.getId(),
                ignored -> new MarketWorker(market.getSymbol())
        );
        return worker.submitAsync(side, orderId, command);
    }

    public boolean isQueued(Long marketId, Long orderId) {
        MarketWorker worker = workers.get(marketId);
        return worker != null && worker.isQueued(orderId);
    }

    private final class MarketWorker implements Runnable {
        private final String marketSymbol;
        private final BlockingQueue<QueuedCommand<?>> queue = new LinkedBlockingQueue<>();
        private final ConcurrentMap<Long, Boolean> queuedOrderIds = new ConcurrentHashMap<>();
        private final AtomicInteger queueDepth = new AtomicInteger();

        private MarketWorker(String marketSymbol) {
            this.marketSymbol = marketSymbol;
            Gauge.builder("order.command.queue.depth", queueDepth, AtomicInteger::get)
                    .tag("market", marketSymbol)
                    .register(meterRegistry);
            Thread.ofPlatform()
                    .daemon(true)
                    .name("market-order-worker-" + marketSymbol)
                    .start(this);
        }

        private void submit(QueuedCommand<?> command) {
            queueDepth.incrementAndGet();
            queue.add(command);
        }

        private boolean submitAsync(OrderSide side, Long orderId, Runnable command) {
            if (queuedOrderIds.putIfAbsent(orderId, Boolean.TRUE) != null) {
                return false;
            }
            queueDepth.incrementAndGet();
            queue.add(new AsyncQueuedCommand(
                    marketSymbol,
                    side,
                    orderId,
                    command,
                    () -> queuedOrderIds.remove(orderId)
            ));
            return true;
        }

        private boolean isQueued(Long orderId) {
            return queuedOrderIds.containsKey(orderId);
        }

        @Override
        public void run() {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    QueuedCommand<?> command = queue.take();
                    queueDepth.decrementAndGet();
                    command.execute();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private class QueuedCommand<T> {
        private final String marketSymbol;
        private final OrderSide side;
        private final Supplier<T> command;
        private final Runnable afterExecute;
        private final long submittedAt = System.nanoTime();
        private final CompletableFuture<T> future = new CompletableFuture<>();

        private QueuedCommand(String marketSymbol, OrderSide side, Supplier<T> command) {
            this(marketSymbol, side, command, () -> {
            });
        }

        private QueuedCommand(String marketSymbol, OrderSide side, Supplier<T> command, Runnable afterExecute) {
            this.marketSymbol = marketSymbol;
            this.side = side;
            this.command = command;
            this.afterExecute = afterExecute;
        }

        private void execute() {
            long startedAt = System.nanoTime();
            stageRecorder.record(marketSymbol, side, "command_queue_wait", startedAt - submittedAt);
            try {
                T result = command.get();
                future.complete(result);
            } catch (Throwable e) {
                future.completeExceptionally(e);
            } finally {
                stageRecorder.record(marketSymbol, side, "command_worker_process", System.nanoTime() - startedAt);
                afterExecute.run();
            }
        }

        private T await() {
            try {
                return future.join();
            } catch (CompletionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                if (cause instanceof Error error) {
                    throw error;
                }
                throw e;
            }
        }
    }

    private final class AsyncQueuedCommand extends QueuedCommand<Void> {
        private final Long orderId;

        private AsyncQueuedCommand(
                String marketSymbol,
                OrderSide side,
                Long orderId,
                Runnable command,
                Runnable afterExecute
        ) {
            super(marketSymbol, side, () -> {
                command.run();
                return null;
            }, afterExecute);
            this.orderId = orderId;
        }

        private Long orderId() {
            return orderId;
        }
    }
}
