package br.com.carvoeiromanager.common.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private static final String DEV_SECRET = "change-me-dev-secret-change-me-dev-secret-0123456789";
    private static final String REAL_SECRET = "not-the-dev-secret-not-the-dev-secret-0123456789";

    @Test
    void refusesThePublicDevSecretOutsideTheDevProfile() {
        // Given: no active profile, as in the deployed jar
        MockEnvironment environment = new MockEnvironment();

        // When/Then
        assertThatThrownBy(() -> new JwtService(new JwtProperties(DEV_SECRET, 60), environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET");
    }

    @Test
    void acceptsThePublicDevSecretInTheDevProfile() {
        // Given
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("dev");

        // When
        JwtService service = new JwtService(new JwtProperties(DEV_SECRET, 60), environment);

        // Then
        assertThat(service.generateToken(UUID.randomUUID(), "jogador")).isNotBlank();
    }

    @Test
    void acceptsARealSecretOutsideTheDevProfile() {
        // Given
        JwtService service = new JwtService(new JwtProperties(REAL_SECRET, 60), new MockEnvironment());
        UUID userId = UUID.randomUUID();

        // When
        String token = service.generateToken(userId, "jogador");

        // Then
        assertThat(service.parse(token).getSubject()).isEqualTo(userId.toString());
    }
}
