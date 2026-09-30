package com.coinflow.support;

import com.coinflow.market.domain.Market;
import com.coinflow.market.repository.MarketRepository;
import com.coinflow.order.domain.OrderSide;
import com.coinflow.order.service.command.MarketOrderCommandQueue;

public final class MarketWorkerBarrier {

    private MarketWorkerBarrier() {
    }

    // 마켓 워커는 마켓별 단일 스레드 FIFO라서 빈 명령이 끝나면 앞선 작업(afterCommit 오더북 반영 포함)도 끝난 상태다.
    // 이전 테스트의 비동기 주문 처리가 다음 테스트 정리 도중 오더북에 반영되는 것을 막는다.
    public static void awaitIdle(MarketRepository marketRepository, MarketOrderCommandQueue commandQueue) {
        for (Market market : marketRepository.findAll()) {
            commandQueue.submit(market, OrderSide.BUY, () -> null);
        }
    }
}
