package br.com.carvoeiromanager.career;

import br.com.carvoeiromanager.support.PostgresTestContainer;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Locks the broken-access-control / IDOR fix: every save-game-scoped game endpoint must enforce
 * ownership. User B (a different authenticated user) must receive 404 for User A's resources,
 * while User A (the owner) keeps getting 2xx on the very same URLs.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class SaveGameOwnershipIT {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void foreignUserGets404OnEveryGameEndpointWhileOwnerStillSucceeds() throws Exception {
        // Given: user A registers and creates a career
        String tokenA = register("ownerA_" + UUID.randomUUID());
        String tokenB = register("foreignB_" + UUID.randomUUID());

        JsonNode career = createCareer(tokenA);
        String saveGameId = career.get("id").asString();
        String clubId = career.get("managedClubId").asString();
        String competitionId = career.get("competitionId").asString();

        String getSave = "/api/v1/save-games/" + saveGameId;
        String advance = "/api/v1/save-games/" + saveGameId + "/advance";
        String lineup = "/api/v1/save-games/" + saveGameId + "/clubs/" + clubId + "/lineup";
        String standings = "/api/v1/save-games/" + saveGameId
                + "/competitions/" + competitionId + "/standings";

        // When/Then: user B (a valid but non-owning token) is denied with 404 on every endpoint.
        mockMvc.perform(get(getSave).header("Authorization", bearer(tokenB)))
                .andExpect(status().isNotFound());
        mockMvc.perform(post(advance).header("Authorization", bearer(tokenB)))
                .andExpect(status().isNotFound());
        mockMvc.perform(put(lineup).header("Authorization", bearer(tokenB))
                        .contentType("application/json")
                        .content(validLineupBody(saveGameId, clubId, tokenA)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(standings).header("Authorization", bearer(tokenB)))
                .andExpect(status().isNotFound());

        // And: the owner (user A) still gets 2xx on the same URLs.
        mockMvc.perform(get(getSave).header("Authorization", bearer(tokenA)))
                .andExpect(status().isOk());
        mockMvc.perform(get(standings).header("Authorization", bearer(tokenA)))
                .andExpect(status().isOk());
        mockMvc.perform(put(lineup).header("Authorization", bearer(tokenA))
                        .contentType("application/json")
                        .content(validLineupBody(saveGameId, clubId, tokenA)))
                .andExpect(status().isOk());
        // advance last: it mutates state, but still must succeed for the owner.
        mockMvc.perform(post(advance).header("Authorization", bearer(tokenA)))
                .andExpect(status().isOk());
    }

    @Test
    void missingRequiredLineupFieldReturns400ValidationError() throws Exception {
        // Given: an owner with a career
        String token = register("validation_" + UUID.randomUUID());
        JsonNode career = createCareer(token);
        String saveGameId = career.get("id").asString();
        String clubId = career.get("managedClubId").asString();

        // When: the body omits the required "formation" field (bean-validation, not a business rule)
        String body = "{\"starters\":[{\"playerId\":\"" + UUID.randomUUID() + "\",\"role\":\"BOX_TO_BOX\"}]}";

        // Then: uniform 400 VALIDATION_ERROR error shape
        mockMvc.perform(put("/api/v1/save-games/" + saveGameId + "/clubs/" + clubId + "/lineup")
                        .header("Authorization", bearer(token))
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void blankManagerNameIsRejectedWithAUniformValidationError() throws Exception {
        String token = register("validation_" + UUID.randomUUID());

        mockMvc.perform(post("/api/v1/save-games")
                        .header("Authorization", bearer(token))
                        .contentType("application/json")
                        .content("{\"managerName\":\"\",\"managerNationality\":\"Brasil\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.status").value(400));
    }

    // --- Helpers ---

    private String register(String username) throws Exception {
        // Usernames must be alphanumeric (^[A-Za-z0-9]+$); strip the '_' and UUID hyphens.
        String clean = username.replaceAll("[^A-Za-z0-9]", "");
        String response = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType("application/json")
                        .content("{\"username\":\"" + clean + "\",\"password\":\"carvoeiro123\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("token").asString();
    }

    private JsonNode createCareer(String token) throws Exception {
        String response = mockMvc.perform(post("/api/v1/save-games")
                        .header("Authorization", bearer(token))
                        .contentType("application/json")
                        .content("{\"managerName\":\"Técnico\",\"managerNationality\":\"Brasil\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    /**
     * Builds a valid 11-man lineup body (1 GK + 10 outfield) for the owner's club by reading the
     * squad through the owner's token.
     */
    private String validLineupBody(String saveGameId, String clubId, String ownerToken) throws Exception {
        String response = mockMvc.perform(get("/api/v1/save-games/" + saveGameId
                        + "/clubs/" + clubId + "/players")
                        .header("Authorization", bearer(ownerToken)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode players = objectMapper.readTree(response).get("players");

        StringBuilder starters = new StringBuilder();
        String gkId = null;
        int outfield = 0;
        for (JsonNode player : players) {
            String position = player.get("position").asString();
            String id = player.get("id").asString();
            // An archetype must be valid for the position it is played in, so it comes from the
            // player's DETAILED position — a coarse-band mapping no longer validates.
            String role = br.com.carvoeiromanager.player.RoleArchetype.defaultFor(
                    br.com.carvoeiromanager.player.DetailedPosition.valueOf(
                            player.get("detailedPosition").asString())).name();
            if ("GK".equals(position) && gkId == null) {
                gkId = id;
                starters.append("{\"playerId\":\"").append(id).append("\",\"role\":\"").append(role).append("\"},");
            } else if (!"GK".equals(position) && outfield < 10) {
                starters.append("{\"playerId\":\"").append(id).append("\",\"role\":\"").append(role).append("\"},");
                outfield++;
            }
        }
        // Remove trailing comma
        String startersJson = starters.substring(0, starters.length() - 1);
        return "{\"formation\":\"4-4-2\",\"starters\":[" + startersJson + "]}";
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
