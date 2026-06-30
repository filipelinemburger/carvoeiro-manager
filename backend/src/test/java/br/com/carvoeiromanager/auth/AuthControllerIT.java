package br.com.carvoeiromanager.auth;

import br.com.carvoeiromanager.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class AuthControllerIT {
    @Autowired MockMvc mockMvc;

    @Test
    void registeringThenReturnsTokenAndUsername() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                .contentType("application/json")
                .content("{\"username\":\"tigre\",\"password\":\"carvoeiro123\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.token").isNotEmpty())
            .andExpect(jsonPath("$.username").value("tigre"));
    }

    @Test
    void duplicateUsernameIsRejectedWithConflict() throws Exception {
        String body = "{\"username\":\"dup\",\"password\":\"carvoeiro123\"}";
        mockMvc.perform(post("/api/v1/auth/register").contentType("application/json").content(body))
            .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/auth/register").contentType("application/json").content(body))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value("USERNAME_TAKEN"));
    }

    @Test
    void loginWithValidCredentialsReturnsOkWithToken() throws Exception {
        String body = "{\"username\":\"loginok\",\"password\":\"carvoeiro123\"}";
        mockMvc.perform(post("/api/v1/auth/register").contentType("application/json").content(body))
            .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/auth/login").contentType("application/json").content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.token").isNotEmpty())
            .andExpect(jsonPath("$.username").value("loginok"));
    }

    @Test
    void loginWithWrongPasswordReturnsUnauthorized() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register").contentType("application/json")
                .content("{\"username\":\"wrongpw\",\"password\":\"carvoeiro123\"}"))
            .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/auth/login").contentType("application/json")
                .content("{\"username\":\"wrongpw\",\"password\":\"not-the-password\"}"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("INVALID_CREDENTIALS"));
    }
}
