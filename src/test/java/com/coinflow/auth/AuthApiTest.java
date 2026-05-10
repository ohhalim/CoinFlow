package com.coinflow.auth;

import com.coinflow.support.TestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.*;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SuppressWarnings({"rawtypes", "unchecked"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfig.class)
class AuthApiTest {

    @Autowired
    private TestRestTemplate restTemplate;

    // ── AUTH-001 회원가입 ──────────────────────────────────────────────

    @Test
    void 회원가입_성공() {
        var response = signup("auth001@example.com", "password1234", "tester");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).containsKeys("userId", "email", "nickname", "status", "createdAt");
        assertThat(response.getBody()).doesNotContainKey("password");
        assertThat(response.getBody().get("email")).isEqualTo("auth001@example.com");
        assertThat(response.getBody().get("status")).isEqualTo("ACTIVE");
    }

    @Test
    void 회원가입_이메일_중복() {
        signup("auth002@example.com", "password1234", "tester");
        var response = signup("auth002@example.com", "password1234", "tester2");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("code")).isEqualTo("DUPLICATE_EMAIL");
    }

    @Test
    void 회원가입_유효성_검증_실패() {
        var response = signup("not-an-email", "password1234", "tester");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("INVALID_REQUEST");
    }

    // ── AUTH-002 로그인 성공 ───────────────────────────────────────────

    @Test
    void 로그인_성공_및_사용자_조회() {
        signup("auth004@example.com", "password1234", "tester");
        var loginResponse = login("auth004@example.com", "password1234");

        assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(loginResponse.getBody().get("tokenType")).isEqualTo("Bearer");

        String token = (String) loginResponse.getBody().get("accessToken");
        assertThat(token).isNotBlank();

        var meResponse = getMe(token);
        assertThat(meResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(meResponse.getBody()).containsKeys("userId", "email", "nickname", "status", "createdAt");
        assertThat(meResponse.getBody().get("email")).isEqualTo("auth004@example.com");
    }

    // ── AUTH-003 로그인 실패 ───────────────────────────────────────────

    @Test
    void 로그인_실패_잘못된_비밀번호() {
        signup("auth005@example.com", "password1234", "tester");
        var response = login("auth005@example.com", "wrongpassword");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().get("code")).isEqualTo("INVALID_CREDENTIALS");
    }

    @Test
    void 로그인_실패_없는_이메일() {
        var response = login("nonexistent@example.com", "password1234");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().get("code")).isEqualTo("INVALID_CREDENTIALS");
    }

    @Test
    void 사용자_조회_토큰_없음() {
        var response = restTemplate.getForEntity("/api/v1/users/me", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ── helpers ───────────────────────────────────────────────────────

    private ResponseEntity<Map> signup(String email, String password, String nickname) {
        return restTemplate.postForEntity(
                "/api/v1/auth/signup",
                Map.of("email", email, "password", password, "nickname", nickname),
                Map.class
        );
    }

    private ResponseEntity<Map> login(String email, String password) {
        return restTemplate.postForEntity(
                "/api/v1/auth/login",
                Map.of("email", email, "password", password),
                Map.class
        );
    }

    private ResponseEntity<Map> getMe(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return restTemplate.exchange(
                "/api/v1/users/me",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map.class
        );
    }
}
