package com.coinflow.wallet.api;

import com.coinflow.wallet.dto.DepositRequest;
import com.coinflow.wallet.dto.WalletLedgerResponse;
import com.coinflow.wallet.dto.WalletResponse;
import com.coinflow.wallet.service.WalletService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/wallets")
public class WalletController {

    private final WalletService walletService;

    @PostMapping("/deposit")
    @ResponseStatus(HttpStatus.OK)
    public WalletResponse deposit(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody DepositRequest request
    ) {
        Long userId = Long.parseLong(jwt.getSubject());
        return walletService.deposit(userId, request);
    }

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
