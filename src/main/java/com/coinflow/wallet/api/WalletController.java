package com.coinflow.wallet.api;

import com.coinflow.wallet.dto.WalletLedgerResponse;
import com.coinflow.wallet.dto.WalletResponse;
import com.coinflow.wallet.service.WalletService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/wallets")
public class WalletController {

    private final WalletService walletService;

    @GetMapping
    public List<WalletResponse> getWallets(@AuthenticationPrincipal Jwt jwt) {
        Long userId = Long.parseLong(jwt.getSubject());
        return walletService.getWallets(userId);
    }

    @GetMapping("/ledgers")
    public List<WalletLedgerResponse> getLedgers(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String asset
    ) {
        Long userId = Long.parseLong(jwt.getSubject());
        return walletService.getLedgers(userId, asset);
    }
}
