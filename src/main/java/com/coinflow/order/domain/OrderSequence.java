package com.coinflow.order.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@NoArgsConstructor
@Table(name = "order_sequences")
public class OrderSequence {

    @Id
    private Long marketId;

    private Long lastSequence;

    public long nextSequence() {
        this.lastSequence += 1;
        return this.lastSequence;
    }
}
