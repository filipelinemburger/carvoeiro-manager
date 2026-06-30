package br.com.carvoeiromanager.common.security;

import br.com.carvoeiromanager.common.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;

/**
 * Returns a 401 with the standard {@link ApiError} JSON body when an
 * unauthenticated request hits a protected route. Used because exceptions
 * raised inside the security filter chain bypass {@code @RestControllerAdvice}.
 */
@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    public RestAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        ApiError body = new ApiError("UNAUTHORIZED", "Authentication required", HttpStatus.UNAUTHORIZED.value());
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
