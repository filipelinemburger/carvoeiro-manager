package br.com.carvoeiromanager.career;

import br.com.carvoeiromanager.player.PlayerRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Covers the "Carregar jogo salvo" backend: listing a user's careers (scoped, newest-first)
 * and deleting one (ownership-checked, cascade).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class SaveGameListDeleteIT {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired PlayerRepository playerRepository;

    @Test
    void listReturnsOnlyTheCallersCareersNewestFirst() throws Exception {
        // Given: user A with two careers and user B with one
        String tokenA = register("listA_" + UUID.randomUUID());
        String tokenB = register("listB_" + UUID.randomUUID());
        String careerA1 = createCareer(tokenA).get("id").asString();
        String careerA2 = createCareer(tokenA).get("id").asString();
        String careerB1 = createCareer(tokenB).get("id").asString();

        // When: A lists their careers
        JsonNode list = listCareers(tokenA);

        // Then: exactly A's two, newest-first (careerA2 created after careerA1), none of B's
        assertThat(list.isArray()).isTrue();
        assertThat(list.size()).isEqualTo(2);
        assertThat(list.get(0).get("id").asString()).isEqualTo(careerA2);
        assertThat(list.get(1).get("id").asString()).isEqualTo(careerA1);
        for (JsonNode c : list) {
            assertThat(c.get("id").asString()).isNotEqualTo(careerB1);
            assertThat(c.has("managedClubName")).isTrue();
            assertThat(c.has("seasonYear")).isTrue();
            assertThat(c.has("createdAt")).isTrue();
        }
    }

    @Test
    void deleteRemovesTheCareerAndCascades_andForeignUserGets404() throws Exception {
        // Given: user A with two careers, user B is a foreign user
        String tokenA = register("delA_" + UUID.randomUUID());
        String tokenB = register("delB_" + UUID.randomUUID());
        String careerA1 = createCareer(tokenA).get("id").asString();
        String careerA2 = createCareer(tokenA).get("id").asString();
        assertThat(playerRepository.findBySaveGameId(UUID.fromString(careerA1))).isNotEmpty();

        // When: A deletes careerA1
        mockMvc.perform(delete("/api/v1/save-games/" + careerA1).header("Authorization", bearer(tokenA)))
                .andExpect(status().isNoContent());

        // Then: careerA1 is gone from A's list and its players were cascade-deleted
        JsonNode list = listCareers(tokenA);
        assertThat(list.size()).isEqualTo(1);
        assertThat(list.get(0).get("id").asString()).isEqualTo(careerA2);
        assertThat(playerRepository.findBySaveGameId(UUID.fromString(careerA1))).isEmpty();

        // And: a foreign user cannot delete A's remaining career (404, and it survives)
        mockMvc.perform(delete("/api/v1/save-games/" + careerA2).header("Authorization", bearer(tokenB)))
                .andExpect(status().isNotFound());
        assertThat(listCareers(tokenA).size()).isEqualTo(1);
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
                        .header("Authorization", bearer(token)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    private JsonNode listCareers(String token) throws Exception {
        String response = mockMvc.perform(get("/api/v1/save-games").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
