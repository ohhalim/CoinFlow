package com.coinflow.wallet.repository;

import com.coinflow.wallet.domain.WalletLedger;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WalletLedgerRepository extends JpaRepository<WalletLedger, Long> {

    List<WalletLedger> findAllByUserIdOrderByCreatedAtDesc(Long userId);

    List<WalletLedger> findAllByUserIdAndAssetOrderByCreatedAtDesc(Long userId, String asset);

    List<WalletLedger> findAllByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    List<WalletLedger> findAllByUserIdAndAssetOrderByCreatedAtDesc(Long userId, String asset, Pageable pageable);
}
