package br.com.carvoeiromanager.youth;

import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRepository;
import br.com.carvoeiromanager.player.Position;
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
 * MockMvc integration test for the YouthController REST endpoints.
 * Mirrors the setup of LiveMatchControllerIT: register a user, create a career, obtain a JWT,
 * seed a prospect via playerRepository, then hit the endpoints.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class YouthControllerIT {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired PlayerRepository playerRepository;

    private String bearer;
    private String saveGameId;
    private String clubId;
    private UUID prospectId;

    @BeforeEach
    void setUp() throws Exception {
        // Register a fresh user and obtain a JWT token
        String username = "youth" + UUID.randomUUID().toString().replaceAll("[^A-Za-z0-9]", "");
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

        // Seed a youth prospect directly via playerRepository (mirrors YouthAcademyServiceIT)
        Player prospect = Player.create(UUID.fromString(saveGameId), UUID.fromString(clubId),
                "Promessa Teste", Position.ATT, 17, "Brasil",
                62, 65, 50, 35, 8, 58, 60);
        prospect.markAsProspect(85);
        playerRepository.save(prospect);
        prospectId = prospect.getId();
    }

    @Test
    void listProspectsReturns200WithPotentialStarsButNoPotentialField() throws Exception {
        mockMvc.perform(get("/api/v1/save-games/{saveGameId}/youth-prospects", saveGameId)
                        .header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + prospectId + "')]").exists())
                .andExpect(jsonPath("$[0].potentialStars").exists())
                .andExpect(jsonPath("$[0].potential").doesNotExist());
    }

    @Test
    void promoteProspectReturns200AndProspectAppearsInSquad() throws Exception {
        // POST promote → 200
        mockMvc.perform(post("/api/v1/save-games/{saveGameId}/youth-prospects/{prospectId}/promote", saveGameId, prospectId)
                        .header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(prospectId.toString()))
                .andExpect(jsonPath("$.potentialStars").exists());

        // GET squad → promoted player appears in the squad
        mockMvc.perform(get("/api/v1/save-games/{s}/clubs/{c}/players", saveGameId, clubId)
                        .header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.players[?(@.id == '" + prospectId + "')]").exists());
    }

    @Test
    void releaseProspectReturns204() throws Exception {
        mockMvc.perform(post("/api/v1/save-games/{saveGameId}/youth-prospects/{prospectId}/release", saveGameId, prospectId)
                        .header("Authorization", bearer))
                .andExpect(status().isNoContent());
    }
}
