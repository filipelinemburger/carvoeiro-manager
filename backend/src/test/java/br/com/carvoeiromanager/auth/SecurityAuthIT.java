package br.com.carvoeiromanager.auth;

import br.com.carvoeiromanager.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Verifies the security filter chain end-to-end: a protected endpoint
 * (GET /api/v1/auth/me) rejects missing/garbage tokens with 401 and accepts a
 * valid Bearer token, resolving it to the authenticated user's id via the
 * {@code @CurrentUser} argument resolver.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class SecurityAuthIT {
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @Test
    void protectedEndpointWithoutTokenReturnsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void protectedEndpointWithGarbageTokenReturnsUnauthorizedNotServerError() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me")
                .header("Authorization", "Bearer not-a-real-jwt"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void protectedEndpointWithValidTokenReturnsCurrentUserId() throws Exception {
        // Given: a registered user with a freshly issued token
        String response = mockMvc.perform(post("/api/v1/auth/register")
                .contentType("application/json")
                .content("{\"username\":\"meuser\",\"password\":\"carvoeiro123\"}"))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        JsonNode auth = objectMapper.readTree(response);
        String token = auth.get("token").asString();
        String expectedUserId = auth.get("userId").asString();

        // When/Then: calling the protected endpoint with the token returns the same user id
        mockMvc.perform(get("/api/v1/auth/me")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.userId").value(expectedUserId));
    }
}
