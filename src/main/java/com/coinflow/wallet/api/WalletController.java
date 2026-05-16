package com.coinflow.wallet.api;

import com.coinflow.wallet.dto.WalletLedgerResponse;
import com.coinflow.wallet.dto.WalletResponse;
import com.coinflow.wallet.service.WalletService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/wallets")
@Validated
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
            @RequestParam(required = false) String asset,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit
    ) {
        Long userId = Long.parseLong(jwt.getSubject());
        return walletService.getLedgers(userId, asset, limit);
    }
}
