package br.com.carvoeiromanager.common.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Injects the authenticated user's {@link java.util.UUID} id into a controller
 * method parameter.
 *
 * <p>Usage:
 * <pre>{@code
 * @GetMapping("/something")
 * public X handle(@CurrentUser UUID userId) { ... }
 * }</pre>
 *
 * <p>Resolved by {@link CurrentUserArgumentResolver}. The id originates from the
 * authentication that {@link JwtAuthFilter} places in the SecurityContext after
 * validating the Bearer token.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CurrentUser {
}
