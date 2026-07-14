package br.com.carvoeiromanager.match;

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
 * MockMvc integration test for the GET single-player REST endpoint — used by contexts that
 * only have a bare player id (transfer market, free agents) and need the full attribute board.
 * Mirrors the setup of PlayerStatsControllerIT: register a user via HTTP, obtain a JWT from the
 * real register response, create a career, then hit the endpoint.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class PlayerControllerIT {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    private String bearer;
    private String saveGameId;
    private String clubId;

    @BeforeEach
    void setUp() throws Exception {
        String username = "playerctrl" + UUID.randomUUID().toString().replaceAll("[^A-Za-z0-9]", "");
        String registerResponse = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType("application/json")
                        .content("{\"username\":\"" + username + "\",\"password\":\"carvoeiro123\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String token = objectMapper.readTree(registerResponse).get("token").asString();
        bearer = "Bearer " + token;

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
    void returnsFullDetailsForAClubSquadPlayer() throws Exception {
        String squadResponse = mockMvc.perform(get("/api/v1/save-games/{s}/clubs/{c}/players", saveGameId, clubId)
                        .header("Authorization", bearer))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode squadPlayer = objectMapper.readTree(squadResponse).get("players").get(0);
        String playerId = squadPlayer.get("id").asString();

        mockMvc.perform(get("/api/v1/save-games/{s}/players/{p}", saveGameId, playerId)
                        .header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(playerId))
                .andExpect(jsonPath("$.name").value(squadPlayer.get("name").asString()))
                .andExpect(jsonPath("$.overall").value(squadPlayer.get("overall").asInt()))
                .andExpect(jsonPath("$.attributes").isArray())
                .andExpect(jsonPath("$.attributes.length()").value(28));
    }

    @Test
    void returnsFullDetailsForAFreeAgentWithNoRole() throws Exception {
        String freeAgentsResponse = mockMvc.perform(get("/api/v1/save-games/{s}/free-agents", saveGameId)
                        .header("Authorization", bearer))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String playerId = objectMapper.readTree(freeAgentsResponse).get(0).get("playerId").asString();

        mockMvc.perform(get("/api/v1/save-games/{s}/players/{p}", saveGameId, playerId)
                        .header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(playerId))
                .andExpect(jsonPath("$.role").doesNotExist())
                .andExpect(jsonPath("$.attributes").isArray());
    }

    @Test
    void rejectsAPlayerIdThatBelongsToADifferentSaveGame() throws Exception {
        // A second, independent save game — its squad players must not be reachable through
        // the first save game's id, even though both belong to the same authenticated user.
        String otherCareerResponse = mockMvc.perform(post("/api/v1/save-games")
                        .header("Authorization", bearer)
                        .contentType("application/json")
                        .content("{\"managerName\":\"Outro\",\"managerNationality\":\"Brasil\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        JsonNode otherCareer = objectMapper.readTree(otherCareerResponse);
        String otherSaveGameId = otherCareer.get("id").asString();
        String otherClubId = otherCareer.get("managedClubId").asString();

        String otherSquadResponse = mockMvc.perform(get("/api/v1/save-games/{s}/clubs/{c}/players", otherSaveGameId, otherClubId)
                        .header("Authorization", bearer))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String otherPlayerId = objectMapper.readTree(otherSquadResponse).get("players").get(0).get("id").asString();

        mockMvc.perform(get("/api/v1/save-games/{s}/players/{p}", saveGameId, otherPlayerId)
                        .header("Authorization", bearer))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("PLAYER_NOT_FOUND"));
    }
}
