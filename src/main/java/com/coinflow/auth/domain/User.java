package com.coinflow.auth.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@NoArgsConstructor
@Table(name = "users")
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String email;
    private String passwordHash;
    private String nickname;

    @Enumerated(EnumType.STRING)
    private UserStatus status;  // ACTIVE, SUSPENDED

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static User create(String email, String passwordHash, String nickname) {
        User user = new User();
        user.email = email;
        user.passwordHash = passwordHash;
        user.nickname = nickname;
        return user;                        
    }
}
