package com.coinflow.auth.dto;

import com.coinflow.auth.domain.User;
import com.coinflow.auth.domain.UserStatus;

import java.time.LocalDateTime;

public record SignupResponse(
        Long userId,
        String email,
        String nickname,
        UserStatus status,
        LocalDateTime createAt
) {
    public static SignupResponse fron(User user) {
        return new SignupResponse(
                user.getId(),
                user.getEmail(),
                user.getNickname(),
                user.getStatus(),
                user.getCreatedAt()
        );   
    }
}
