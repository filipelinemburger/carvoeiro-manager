package br.com.carvoeiromanager.stats;

import br.com.carvoeiromanager.support.PostgresTestContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc integration test for the GET player-stats REST endpoint.
 * Mirrors the setup of LiveMatchControllerIT / YouthControllerIT: register a user via HTTP,
 * obtain a JWT from the real register response, create a career, then hit the endpoint.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class PlayerStatsControllerIT {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    private String bearer;
    private String saveGameId;
    private String clubId;

    @BeforeEach
    void setUp() throws Exception {
        // Register a fresh user and obtain a JWT token
        String username = "statsctrl" + UUID.randomUUID().toString().replaceAll("[^A-Za-z0-9]", "");
        String registerResponse = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType("application/json")
                        .content("{\"username\":\"" + username + "\",\"password\":\"carvoeiro123\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String token = objectMapper.readTree(registerResponse).get("token").asString();
        bearer = "Bearer " + token;

        // Create a career (save game) for this user
        String careerResponse = mockMvc.perform(post("/api/v1/save-games")
                        .header("Authorization", bearer)
                        .contentType("application/json")
                        .content("{\"managerName\":\"Técnico\",\"managerNationality\":\"Brasil\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        JsonNode career = objectMapper.readTree(careerResponse);
        saveGameId = career.get("id").asString();
        clubId = career.get("managedClubId").asString();
    }

    @Test
    void returnsStatsForAnOwnedSave() throws Exception {
        // Grab any player from the managed club's squad via the real squad endpoint
        String squadResponse = mockMvc.perform(get("/api/v1/save-games/{s}/clubs/{c}/players", saveGameId, clubId)
                        .header("Authorization", bearer))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String playerId = objectMapper.readTree(squadResponse).get("players").get(0).get("id").asString();

        mockMvc.perform(get("/api/v1/save-games/{saveGameId}/players/{playerId}/stats", saveGameId, playerId)
                        .header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seasons").isArray())
                .andExpect(jsonPath("$.totals.goals").value(0));
    }
}
