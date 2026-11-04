package br.com.carvoeiromanager.common.security;

import br.com.carvoeiromanager.common.ApiException;
import br.com.carvoeiromanager.common.ErrorCode;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;

/**
 * Issues and validates HS256 JWTs. The token subject carries the user id and a
 * {@code username} claim is added for convenience.
 */
@Service
public class JwtService {

    private static final String USERNAME_CLAIM = "username";

    /**
     * The development JWT secret from {@code backend/config/application-dev.yml}. It is public, so
     * anyone could forge tokens signed with it: startup fails if it is in use outside the
     * {@code dev} profile.
     */
    private static final String DEV_SECRET =
            "change-me-dev-secret-change-me-dev-secret-0123456789";

    private final SecretKey key;
    private final long expirationMinutes;

    public JwtService(JwtProperties properties, Environment environment) {
        if (DEV_SECRET.equals(properties.secret()) && !environment.acceptsProfiles(Profiles.of("dev"))) {
            throw new IllegalStateException(
                    "JWT_SECRET is the public development secret. Generate a real one: openssl rand -hex 48");
        }
        this.key = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
        this.expirationMinutes = properties.expirationMinutes();
    }

    public String generateToken(UUID userId, String username) {
        Instant now = Instant.now();
        Instant expiry = now.plus(expirationMinutes, ChronoUnit.MINUTES);
        return Jwts.builder()
                .subject(userId.toString())
                .claim(USERNAME_CLAIM, username)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(key)
                .compact();
    }

    /**
     * Validates the signature and expiry and returns the claims.
     *
     * @throws ApiException with code {@code INVALID_TOKEN} (401) if the token is
     *                      malformed, has a bad signature, or is expired
     */
    public Claims parse(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (JwtException | IllegalArgumentException ex) {
            throw new ApiException(ErrorCode.INVALID_TOKEN, "Invalid or expired token");
        }
    }
}
