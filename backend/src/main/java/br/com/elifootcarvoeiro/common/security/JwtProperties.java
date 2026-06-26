package br.com.elifootcarvoeiro.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code app.jwt.*} from application configuration.
 *
 * @param secret            HMAC secret; must be at least 32 bytes for HS256
 * @param expirationMinutes token lifetime in minutes
 */
@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(String secret, long expirationMinutes) {}
