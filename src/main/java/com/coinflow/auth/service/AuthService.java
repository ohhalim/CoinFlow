package com.coinflow.auth.service;

import com.coinflow.auth.domain.User;
import com.coinflow.auth.dto.LoginRequest;
import com.coinflow.auth.dto.SignupRequest;
import com.coinflow.auth.dto.SignupResponse;
import com.coinflow.auth.dto.TokenResponse;
import com.coinflow.auth.repository.UserRepository;
import com.coinflow.common.exception.ApiException;
import com.coinflow.common.exception.ErrorCode;
import com.coinflow.market.domain.AssetStatus;
import com.coinflow.market.repository.AssetRepository;
import com.coinflow.wallet.domain.Wallet;
import com.coinflow.wallet.repository.WalletRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AssetRepository assetRepository;
    private final WalletRepository walletRepository;

    public AuthService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            AssetRepository assetRepository,
            WalletRepository walletRepository
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.assetRepository = assetRepository;
        this.walletRepository = walletRepository;
    }

    @Transactional
    public SignupResponse signup(SignupRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new ApiException(ErrorCode.DUPLICATE_EMAIL);
        }

        String passwordHash = passwordEncoder.encode(request.password());
        User user = User.create(request.email(), passwordHash, request.nickname());
        User savedUser = userRepository.save(user);

        assetRepository.findAllByStatus(AssetStatus.ACTIVE)
                .stream()
                .map(asset -> Wallet.create(savedUser.getId(), asset.getCode()))
                .forEach(walletRepository::save);

        return SignupResponse.from(savedUser);
    }

    @Transactional(readOnly = true)
    public TokenResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new ApiException(ErrorCode.INVALID_CREDENTIALS));

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS);
        }

        String accessToken = jwtService.generateAccessToken(user);
        return TokenResponse.of(accessToken, jwtService.getExpiresInSeconds(), user);
    }

    @Transactional(readOnly = true)
    public SignupResponse getMe(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.USER_NOT_FOUND));
        return SignupResponse.from(user);
    }
}
