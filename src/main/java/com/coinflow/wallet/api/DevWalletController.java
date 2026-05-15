package com.coinflow.wallet.api;

import com.coinflow.wallet.dto.DepositRequest;
import com.coinflow.wallet.dto.WalletResponse;
import com.coinflow.wallet.service.WalletService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Profile("!prod")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/wallets")
public class DevWalletController {

    private final WalletService walletService;

    @PostMapping("/deposit")
    @ResponseStatus(HttpStatus.OK)
    public WalletResponse deposit(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody DepositRequest request
    ) {
        Long userId = Long.parseLong(jwt.getSubject());
        return walletService.deposit(userId, request);
    }
}
