package br.com.carvoeiromanager.auth;

import br.com.carvoeiromanager.career.SaveGameRepository;
import br.com.carvoeiromanager.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Deleting an account must permanently remove the user and every row tied to it
 * across all account-scoped tables (via ON DELETE CASCADE), while leaving other
 * users' data untouched.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class AccountDeletionIT {

    // Every table that stores per-account game data (keyed by save_game_id).
    // After deletion none of these may hold a row for the deleted save game.
    private static final String[] SCOPED_TABLES = {
            "clubs", "players", "competitions", "fixtures", "lineups",
            "competition_club", "season_event", "finance_transaction",
            "transfer_offer", "news_item", "season",
    };

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    UserRepository userRepository;

    @Autowired
    SaveGameRepository saveGameRepository;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void deletingAnAccountRemovesTheUserAndAllRelatedDataAcrossEveryTable() throws Exception {
        // User A registers, creates a career, sets a line-up and plays a round so that
        // game data lands in clubs/players/fixtures/lineups/finances/news/match events.
        JsonNode authA = register("del_" + UUID.randomUUID());
        String tokenA = authA.get("token").asString();
        UUID userIdA = UUID.fromString(authA.get("userId").asString());
        JsonNode careerA = createCareer(tokenA);
        UUID saveGameA = UUID.fromString(careerA.get("id").asString());
        String clubIdA = careerA.get("managedClubId").asString();

        setLineup(saveGameA.toString(), clubIdA, tokenA);
        advance(saveGameA.toString(), tokenA);

        // A second, unrelated user with their own career (must survive the deletion).
        JsonNode authB = register("keep_" + UUID.randomUUID());
        String tokenB = authB.get("token").asString();
        UUID saveGameB = UUID.fromString(createCareer(tokenB).get("id").asString());

        // Sanity: A really has data spread across several tables.
        assertThat(count("clubs", saveGameA)).isGreaterThan(0);
        assertThat(count("players", saveGameA)).isGreaterThan(0);
        assertThat(count("lineups", saveGameA)).isGreaterThan(0);
        assertThat(count("fixtures", saveGameA)).isGreaterThan(0);

        // When: A deletes the account.
        mockMvc.perform(delete("/api/v1/auth/account").header("Authorization", bearer(tokenA)))
                .andExpect(status().isNoContent());

        // Then: the user, the save game and every account-scoped table are cleared.
        assertThat(userRepository.existsById(userIdA)).isFalse();
        assertThat(saveGameRepository.findById(saveGameA)).isEmpty();
        for (String table : SCOPED_TABLES) {
            assertThat(count(table, saveGameA))
                    .as("rows left in %s for the deleted account", table)
                    .isZero();
        }
        // Match events hang off fixtures (no save_game_id); they must be gone too.
        assertThat(matchEventsFor(saveGameA)).isZero();

        // And: A's former resource now 404s, while B is completely untouched.
        mockMvc.perform(get("/api/v1/save-games/" + saveGameA).header("Authorization", bearer(tokenA)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/save-games/" + saveGameB).header("Authorization", bearer(tokenB)))
                .andExpect(status().isOk());
        assertThat(count("clubs", saveGameB)).isGreaterThan(0);
    }

    @Test
    void deletingAnAccountWithoutATokenIsUnauthorized() throws Exception {
        mockMvc.perform(delete("/api/v1/auth/account"))
                .andExpect(status().isUnauthorized());
    }

    // --- Helpers ---

    private int count(String table, UUID saveGameId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE save_game_id = ?", Integer.class, saveGameId);
        return n == null ? 0 : n;
    }

    private int matchEventsFor(UUID saveGameId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM match_events me JOIN fixtures f ON f.id = me.fixture_id "
                        + "WHERE f.save_game_id = ?", Integer.class, saveGameId);
        return n == null ? 0 : n;
    }

    private JsonNode register(String username) throws Exception {
        String response = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType("application/json")
                        .content("{\"username\":\"" + username + "\",\"password\":\"carvoeiro123\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    private JsonNode createCareer(String token) throws Exception {
        String response = mockMvc.perform(post("/api/v1/save-games")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    private void setLineup(String saveGameId, String clubId, String token) throws Exception {
        mockMvc.perform(put("/api/v1/save-games/" + saveGameId + "/clubs/" + clubId + "/lineup")
                        .header("Authorization", bearer(token))
                        .contentType("application/json")
                        .content(validLineupBody(saveGameId, clubId, token)))
                .andExpect(status().isOk());
    }

    private void advance(String saveGameId, String token) throws Exception {
        mockMvc.perform(post("/api/v1/save-games/" + saveGameId + "/advance")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk());
    }

    /** Builds a valid 11-man 4-4-2 lineup (1 GK + 10 outfield) for the owner's club. */
    private String validLineupBody(String saveGameId, String clubId, String ownerToken) throws Exception {
        String response = mockMvc.perform(get("/api/v1/save-games/" + saveGameId
                        + "/clubs/" + clubId + "/players")
                        .header("Authorization", bearer(ownerToken)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode players = objectMapper.readTree(response).get("players");

        StringBuilder starters = new StringBuilder();
        String gkId = null;
        int def = 0, mid = 0, att = 0;
        for (JsonNode player : players) {
            String position = player.get("position").asString();
            String id = player.get("id").asString();
            // Fill a 4-4-2: 1 GK, 4 DEF, 4 MID, 2 ATT.
            boolean take;
            String role;
            switch (position) {
                case "GK" -> { take = gkId == null; role = "GOALKEEPER"; if (take) gkId = id; }
                case "DEF" -> { take = def < 4; role = "FULL_BACK"; if (take) def++; }
                case "MID" -> { take = mid < 4; role = "BOX_TO_BOX"; if (take) mid++; }
                default -> { take = att < 2; role = "POACHER"; if (take) att++; }
            }
            if (take) {
                starters.append("{\"playerId\":\"").append(id).append("\",\"role\":\"").append(role).append("\"},");
            }
        }
        String startersJson = starters.substring(0, starters.length() - 1);
        return "{\"formation\":\"4-4-2\",\"starters\":[" + startersJson + "]}";
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
