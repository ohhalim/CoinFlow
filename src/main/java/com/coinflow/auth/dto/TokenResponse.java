package com.coinflow.auth.dto;

import com.coinflow.auth.domain.User;
import com.coinflow.auth.domain.UserStatus;

public record TokenResponse(
        String accessToken,
        String tokenType,
        long expiresIn,
        UserInfo user
) {
    public static TokenResponse of(String accessToken, long expiresIn, User user) {
        return new TokenResponse(
                accessToken,
                "Bearer",
                expiresIn,
                UserInfo.from(user)
        );
    }

    public record UserInfo(
            Long userId,
            String email,
            String nickname,
            UserStatus status
    ) {
        public static UserInfo from(User user) {
            return new UserInfo(
                    user.getId(),
                    user.getEmail(),
                    user.getNickname(),
                    user.getStatus()
            );
        }
    }
}
