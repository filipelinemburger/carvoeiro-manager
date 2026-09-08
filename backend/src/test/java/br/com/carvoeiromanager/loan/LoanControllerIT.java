package br.com.carvoeiromanager.loan;

import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRepository;
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
 * MockMvc integration test for {@link LoanController}'s two paths added by Task 6 (round 1):
 * {@code GET .../loans} and {@code POST .../loans/{offerId}/withdraw}.
 * <p>
 * Everything about the negotiation itself is already pinned at the service layer
 * ({@code LoanServiceOffersIT} and friends) — those tests call {@code LoanService} directly and
 * would pass unchanged even if the controller mapped the wrong URL segment (the copy source for
 * this task was {@code @GetMapping("/offers")} / {@code @PostMapping("/offers/{offerId}/withdraw")}).
 * Only a real MockMvc round-trip against the routes the frontend actually calls
 * ({@code career.ts}'s {@code /loans} and {@code /loans/{offerId}/withdraw}) catches that class of
 * mistake. Mirrors {@code YouthControllerIT}'s setup — the structural analogue: a save-scoped
 * controller under {@code /api/v1/save-games/{saveGameId}/...} with a GET listing and POST actions.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class LoanControllerIT {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired PlayerRepository playerRepository;
    @Autowired LoanOfferRepository loanOfferRepository;

    private String bearer;
    private String saveGameId;
    private String clubId;

    @BeforeEach
    void setUp() throws Exception {
        // Register a fresh user and obtain a JWT token (mirrors YouthControllerIT).
        String username = "loanctrl" + UUID.randomUUID().toString().replaceAll("[^A-Za-z0-9]", "");
        String registerResponse = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType("application/json")
                        .content("{\"username\":\"" + username + "\",\"password\":\"carvoeiro123\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String token = objectMapper.readTree(registerResponse).get("token").asString();
        bearer = "Bearer " + token;

        // Create a career (save game) for this user.
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

    /**
     * Seeds a COUNTERED loan offer directly via the repository — the negotiation that produces
     * one is LoanService's own concern (already covered elsewhere); this class only needs a row
     * to exist so the two HTTP paths have something real to list/withdraw.
     */
    private UUID seedCounteredOffer() {
        UUID borrowerClubId = playerRepository.findBySaveGameId(UUID.fromString(saveGameId)).stream()
                .map(Player::getClubId)
                .filter(id -> id != null && !id.toString().equals(clubId))
                .findFirst().orElseThrow();
        UUID playerId = playerRepository.findByClubIdAndYouthProspectFalse(UUID.fromString(clubId))
                .stream().findFirst().map(Player::getId).orElseThrow();
        LoanOffer offer = LoanOffer.create(UUID.fromString(saveGameId), playerId,
                UUID.fromString(clubId), borrowerClubId, 50, false, 0);
        offer.markCountered(70, true);
        loanOfferRepository.save(offer);
        return offer.getId();
    }

    @Test
    void getLoansReturns200WithTheSeededCounteredOffer() throws Exception {
        UUID offerId = seedCounteredOffer();

        mockMvc.perform(get("/api/v1/save-games/{saveGameId}/loans", saveGameId)
                        .header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.offerId == '" + offerId + "')]").exists())
                .andExpect(jsonPath("$[0].status").value("COUNTERED"))
                // The managed club is the owner here — the offer was seeded lending its player out.
                .andExpect(jsonPath("$[0].managedClubIsOwner").value(true));
    }

    @Test
    void withdrawReturns200AndTheOfferNoLongerAppearsInTheListing() throws Exception {
        UUID offerId = seedCounteredOffer();

        mockMvc.perform(post("/api/v1/save-games/{saveGameId}/loans/{offerId}/withdraw", saveGameId, offerId)
                        .header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.offerId").value(offerId.toString()))
                .andExpect(jsonPath("$.status").value("WITHDRAWN"));

        mockMvc.perform(get("/api/v1/save-games/{saveGameId}/loans", saveGameId)
                        .header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.offerId == '" + offerId + "')]").doesNotExist());
    }
}
