package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.support.PostgresTestContainer;
import com.jayway.jsonpath.JsonPath;
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
 * MockMvc integration test for the five live-match REST endpoints.
 * Drives the full lifecycle over HTTP: kickoff → GET → advance loop → finish → GET (204).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class LiveMatchControllerIT {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    private String bearer;
    private String saveGameId;

    @BeforeEach
    void setUp() throws Exception {
        // Register a fresh user and obtain a JWT token
        String username = "livematch" + UUID.randomUUID().toString().replaceAll("[^A-Za-z0-9]", "");
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
        String managedClubId = career.get("managedClubId").asString();

        // Set lineup via HTTP so the save game is playable (best XI via the lineup service)
        // We need to seed a valid lineup — hit the squad endpoint to get player ids and then set the lineup
        String squadResponse = mockMvc.perform(get("/api/v1/save-games/{s}/clubs/{c}/players", saveGameId, managedClubId)
                        .header("Authorization", bearer))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Build a valid XI: exactly 1 GK + 10 outfield players as starters, 3 outfield as bench.
        // The squad API returns position as GK/DEF/MID/ATT band names.
        JsonNode players = objectMapper.readTree(squadResponse).get("players");
        StringBuilder starters = new StringBuilder("[");
        StringBuilder bench = new StringBuilder("[");
        boolean gkPicked = false;
        int outfieldStarters = 0;
        int benchCount = 0;
        for (JsonNode player : players) {
            boolean eligible = player.has("eligible") && player.get("eligible").asBoolean();
            if (!eligible) continue;
            String pid = player.get("id").asString();
            String position = player.get("position").asString();
            String role = defaultRole(position);
            boolean isGk = "GK".equals(position);
            if (isGk && !gkPicked) {
                // First GK goes into starters
                if (starters.length() > 1) starters.append(",");
                starters.append("{\"playerId\":\"").append(pid).append("\",\"role\":\"").append(role).append("\"}");
                gkPicked = true;
            } else if (!isGk && outfieldStarters < 10) {
                // First 10 non-GK eligible players go into starters
                if (starters.length() > 1) starters.append(",");
                starters.append("{\"playerId\":\"").append(pid).append("\",\"role\":\"").append(role).append("\"}");
                outfieldStarters++;
            } else if (!isGk && benchCount < 3) {
                // Next 3 non-GK eligible players go on the bench
                if (bench.length() > 1) bench.append(",");
                bench.append("{\"playerId\":\"").append(pid).append("\",\"role\":\"").append(role).append("\"}");
                benchCount++;
            }
            if (gkPicked && outfieldStarters == 10 && benchCount == 3) break;
        }
        starters.append("]");
        bench.append("]");

        String lineupBody = "{\"formation\":\"4-4-2\",\"starters\":" + starters + ",\"bench\":" + bench + "}";
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/v1/save-games/{s}/clubs/{c}/lineup", saveGameId, managedClubId)
                        .header("Authorization", bearer)
                        .contentType("application/json")
                        .content(lineupBody))
                .andExpect(status().isOk());
    }

    @Test
    void fullLiveMatchFlowOverHttp() throws Exception {
        // kickoff → 200 with status IN_PROGRESS
        mockMvc.perform(post("/api/v1/save-games/{s}/live-match/kickoff", saveGameId)
                        .header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));

        // GET → 200 (session exists)
        mockMvc.perform(get("/api/v1/save-games/{s}/live-match", saveGameId)
                        .header("Authorization", bearer))
                .andExpect(status().isOk());

        // advance until FULL_TIME (bounded at 200 iterations)
        String status = "IN_PROGRESS";
        for (int i = 0; i < 200 && !"FULL_TIME".equals(status); i++) {
            String body = mockMvc.perform(post("/api/v1/save-games/{s}/live-match/advance", saveGameId)
                            .header("Authorization", bearer))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            status = JsonPath.read(body, "$.status");
        }

        // finish → 200 with type MATCH
        mockMvc.perform(post("/api/v1/save-games/{s}/live-match/finish", saveGameId)
                        .header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("MATCH"));

        // GET after finish → 204 (session gone)
        mockMvc.perform(get("/api/v1/save-games/{s}/live-match", saveGameId)
                        .header("Authorization", bearer))
                .andExpect(status().isNoContent());
    }

    @Test
    void advanceToFullTimeOverHttpReachesFullTimeInOneCall() throws Exception {
        mockMvc.perform(post("/api/v1/save-games/{s}/live-match/kickoff", saveGameId)
                        .header("Authorization", bearer))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/save-games/{s}/live-match/advance-to", saveGameId)
                        .header("Authorization", bearer)
                        .contentType("application/json")
                        .content("{\"target\":\"FULL_TIME\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FULL_TIME"))
                .andExpect(jsonPath("$.minute").value(90));
    }

    @Test
    void advanceToWithAnUnknownTargetIsRejectedWith400() throws Exception {
        mockMvc.perform(post("/api/v1/save-games/{s}/live-match/kickoff", saveGameId)
                        .header("Authorization", bearer))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/save-games/{s}/live-match/advance-to", saveGameId)
                        .header("Authorization", bearer)
                        .contentType("application/json")
                        .content("{\"target\":\"NOT_A_REAL_TARGET\"}"))
                .andExpect(status().isBadRequest());
    }

    // --- helpers ---

    private String defaultRole(String position) {
        return switch (position) {
            case "GK" -> "GOALKEEPER";
            case "DEF" -> "FULL_BACK";
            case "MID" -> "BOX_TO_BOX";
            default -> "POACHER";  // ATT
        };
    }
}
