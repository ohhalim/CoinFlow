package com.coinflow.market.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@Entity
@NoArgsConstructor
@Table(name = "assets")
public class Asset {

    @Id
    private String code;

    private String name;
    private String displayName;

    @Enumerated(EnumType.STRING)
    private AssetStatus status;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
