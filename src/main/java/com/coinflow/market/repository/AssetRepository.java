package com.coinflow.market.repository;

import com.coinflow.market.domain.Asset;
import com.coinflow.market.domain.AssetStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AssetRepository extends JpaRepository<Asset, String> {
    List<Asset> findAllByStatus(AssetStatus status);
}
