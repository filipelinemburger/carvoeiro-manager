package br.com.carvoeiromanager.common.security;

import br.com.carvoeiromanager.common.ApiException;
import br.com.carvoeiromanager.common.ErrorCode;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
     * The dev-default JWT secret committed in {@code application.yml}. If this exact value is in
     * use at startup we emit a prominent warning; production must override it via {@code JWT_SECRET}.
     */
    private static final String DEFAULT_DEV_SECRET =
            "change-me-dev-secret-change-me-dev-secret-0123456789";

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    private final SecretKey key;
    private final long expirationMinutes;

    public JwtService(JwtProperties properties) {
        this.key = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
        this.expirationMinutes = properties.expirationMinutes();

        if (DEFAULT_DEV_SECRET.equals(properties.secret())) {
            log.warn("################################################################");
            log.warn("# SECURITY WARNING: the default development JWT secret is in   #");
            log.warn("# use. This is INSECURE for production. Override it by setting  #");
            log.warn("# the JWT_SECRET environment variable to a strong secret.      #");
            log.warn("################################################################");
        }
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
