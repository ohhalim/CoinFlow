package com.coinflow.websocket.dto;

import java.util.List;

public record OrderBookSnapshotMessage(
        Long eventId,
        String market,
        List<PriceLevel> bids,
        List<PriceLevel> asks
) {
    public record PriceLevel(String price, String quantity) {
    }
}
