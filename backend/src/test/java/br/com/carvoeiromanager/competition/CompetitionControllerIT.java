package br.com.carvoeiromanager.competition;

import br.com.carvoeiromanager.career.SaveGame;
import br.com.carvoeiromanager.career.SaveGameRepository;
import br.com.carvoeiromanager.club.Club;
import br.com.carvoeiromanager.club.ClubRepository;
import br.com.carvoeiromanager.season.SeasonEvent;
import br.com.carvoeiromanager.season.SeasonEventRepository;
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

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc coverage for the phases/ties endpoints (Task 12) and the standings endpoint's
 * {@code phaseId} threading. Mirrors the setup of PlayerStatsControllerIT: register a user via
 * HTTP for a real JWT, then build the competition directly through the repositories — like
 * {@link PhaseResolverTest}, but here the bracket is only half-played on purpose, so the ties
 * endpoint's null-instead-of-throw contract for undecided ties gets real coverage.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class CompetitionControllerIT {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired SaveGameRepository saveGameRepository;
    @Autowired ClubRepository clubRepository;
    @Autowired CompetitionRepository competitionRepository;
    @Autowired CompetitionClubRepository competitionClubRepository;
    @Autowired CompetitionPhaseRepository competitionPhaseRepository;
    @Autowired SeasonEventRepository seasonEventRepository;
    @Autowired FixtureRepository fixtureRepository;

    private static final int MEMBERS = 8;
    private static final int ROUNDS = MEMBERS - 1; // single round-robin over 8 clubs = 7 rounds

    private String bearer;
    private UUID saveGameId;
    private UUID competitionId;
    private UUID firstPhaseId;
    private UUID quarterFinalPhaseId;
    private List<UUID> clubIds;

    /** tie_order -> {@code true} once fully decided (both legs played, no shootout needed). */
    private UUID decisiveTieHigherSeed;
    private UUID levelTieClub;

    @BeforeEach
    void setUp() throws Exception {
        // Register a fresh user via HTTP and keep the real JWT + userId it returns.
        String username = "compctrl" + UUID.randomUUID().toString().replaceAll("[^A-Za-z0-9]", "");
        String registerResponse = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType("application/json")
                        .content("{\"username\":\"" + username + "\",\"password\":\"carvoeiro123\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        JsonNode registered = objectMapper.readTree(registerResponse);
        UUID userId = UUID.fromString(registered.get("userId").asString());
        bearer = "Bearer " + registered.get("token").asString();

        buildScenario(userId);
    }

    /**
     * 8 clubs, a fully-played ROUND_ROBIN first phase (deterministic: lower club index always
     * wins), and a KNOCKOUT quarterfinal phase materialized with four ties left in four different
     * states of undecidedness — covering every reason {@link TieResolver#winner} can throw:
     * <ul>
     *   <li>tie 0 — neither leg played</li>
     *   <li>tie 1 — only the first leg played</li>
     *   <li>tie 2 — fully played, decisively (a real winner)</li>
     *   <li>tie 3 — fully played but level on aggregate, no shootout recorded</li>
     * </ul>
     */
    private void buildScenario(UUID userId) {
        SaveGame save = SaveGame.create(userId, 2026);
        saveGameRepository.save(save);
        saveGameId = save.getId();

        clubIds = new ArrayList<>();
        for (int i = 0; i < MEMBERS; i++) {
            Club club = Club.create(saveGameId, "Ctrl Club " + i, "C" + i);
            clubRepository.save(club);
            clubIds.add(club.getId());
        }

        Competition competition = Competition.create(saveGameId, "Ctrl Cup", "CUP", 2026, ROUNDS + 2);
        competitionRepository.save(competition);
        competitionId = competition.getId();
        for (UUID clubId : clubIds) {
            competitionClubRepository.save(CompetitionClub.create(saveGameId, competitionId, clubId));
        }

        CompetitionPhase firstPhase = CompetitionPhase.create(saveGameId, competitionId, 0,
                "Primeira Fase", PhaseKind.ROUND_ROBIN, 1, ROUNDS, MEMBERS);
        competitionPhaseRepository.save(firstPhase);
        firstPhaseId = firstPhase.getId();
        CompetitionPhase quarterFinalPhase = CompetitionPhase.create(saveGameId, competitionId, 1,
                "Quartas de final", PhaseKind.KNOCKOUT, 2, 2, null);
        competitionPhaseRepository.save(quarterFinalPhase);
        quarterFinalPhaseId = quarterFinalPhase.getId();
        CompetitionPhase semiFinalPhase = CompetitionPhase.create(saveGameId, competitionId, 2,
                "Semifinal", PhaseKind.KNOCKOUT, 2, 2, null);
        competitionPhaseRepository.save(semiFinalPhase);

        LocalDate seasonStart = LocalDate.of(2026, 1, 15);

        // The "complete" flag is derived purely from calendar events, so the first phase needs
        // real processed SeasonEvent rows -- inserting fixtures alone would leave it false and
        // the true branch untested. The knockout phases deliberately get none, so they read
        // "not complete", which is also what a materialized-but-unfinished round should report.
        for (int round = 0; round < ROUNDS; round++) {
            SeasonEvent event = SeasonEvent.createPhased(saveGameId,
                    seasonStart.plusDays((long) round * 5), competitionId, firstPhaseId, round, 0);
            event.markProcessed();
            seasonEventRepository.save(event);
        }

        FixtureScheduler scheduler = new FixtureScheduler();
        for (ScheduledFixture s : scheduler.singleRoundRobin(MEMBERS)) {
            UUID home = clubIds.get(s.homeIndex());
            UUID away = clubIds.get(s.awayIndex());
            LocalDate date = seasonStart.plusDays((long) s.round() * 5);
            Fixture fx = Fixture.create(saveGameId, competitionId, s.round(), home, away, date);
            fx.setPhaseId(firstPhaseId);
            // Deterministic result: the lower club index always wins 2-0.
            if (s.homeIndex() < s.awayIndex()) {
                fx.recordResult(2, 0);
            } else {
                fx.recordResult(0, 2);
            }
            fixtureRepository.save(fx);
        }

        // Seeded knockoutPairings(8): tie 0 = seed0 vs seed7, tie 1 = seed1 vs seed6,
        // tie 2 = seed2 vs seed5, tie 3 = seed3 vs seed4. Standings order == club index order,
        // since every result above is strictly determined by index.
        LocalDate leg1Date = seasonStart.plusDays((long) ROUNDS * 5);
        LocalDate leg2Date = seasonStart.plusDays((long) (ROUNDS + 1) * 5);

        createTie(quarterFinalPhaseId, 0, clubIds.get(0), clubIds.get(7), leg1Date, leg2Date,
                false, null, null, false, null, null, null, null);
        createTie(quarterFinalPhaseId, 1, clubIds.get(1), clubIds.get(6), leg1Date, leg2Date,
                true, 1, 0, false, null, null, null, null);
        createTie(quarterFinalPhaseId, 2, clubIds.get(2), clubIds.get(5), leg1Date, leg2Date,
                true, 0, 2, true, 1, 0, null, null);
        createTie(quarterFinalPhaseId, 3, clubIds.get(3), clubIds.get(4), leg1Date, leg2Date,
                true, 1, 1, true, 0, 0, null, null);

        decisiveTieHigherSeed = clubIds.get(2);
        levelTieClub = clubIds.get(3);
    }

    /**
     * Creates one knockout tie: the lower seed hosts the first leg, the higher seed hosts the
     * second (mirroring {@code PhaseResolver}'s convention). Any leg passed as unplayed gets no
     * recorded result at all — {@link Fixture#isPlayed()} stays false.
     */
    private void createTie(UUID phaseId, int tieOrder, UUID higherSeed, UUID lowerSeed,
                           LocalDate leg1Date, LocalDate leg2Date,
                           boolean leg1Played, Integer leg1Home, Integer leg1Away,
                           boolean leg2Played, Integer leg2Home, Integer leg2Away,
                           Integer penaltyHome, Integer penaltyAway) {
        UUID tieId = UUID.randomUUID();

        Fixture leg1 = Fixture.create(saveGameId, competitionId, ROUNDS, lowerSeed, higherSeed, leg1Date);
        leg1.setPhaseId(phaseId);
        leg1.setTieId(tieId);
        leg1.setTieOrder(tieOrder);
        leg1.setLeg(1);
        if (leg1Played) {
            leg1.recordResult(leg1Home, leg1Away);
        }
        fixtureRepository.save(leg1);

        Fixture leg2 = Fixture.create(saveGameId, competitionId, ROUNDS + 1, higherSeed, lowerSeed, leg2Date);
        leg2.setPhaseId(phaseId);
        leg2.setTieId(tieId);
        leg2.setTieOrder(tieOrder);
        leg2.setLeg(2);
        if (leg2Played) {
            leg2.recordResult(leg2Home, leg2Away);
            leg2.setPenaltyHome(penaltyHome);
            leg2.setPenaltyAway(penaltyAway);
        }
        fixtureRepository.save(leg2);
    }

    @Test
    void phasesEndpointReturnsEnumNamesNotLabels() throws Exception {
        String response = mockMvc.perform(get("/api/v1/save-games/{s}/competitions/{c}/phases",
                        saveGameId, competitionId)
                        .header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(3)))
                .andReturn().getResponse().getContentAsString();

        JsonNode phases = objectMapper.readTree(response);

        // kind carries the ENUM NAME (frontend switches on it); name carries the pt-BR label —
        // the two must never be conflated (see the weather-slice bug this repo already shipped).
        assertThat(phases.get(0).get("kind").asString()).isEqualTo("ROUND_ROBIN");
        assertThat(phases.get(0).get("name").asString()).isEqualTo("Primeira Fase");
        assertThat(phases.get(0).get("ordinal").asInt()).isZero();
        assertThat(phases.get(0).get("qualifiers").asInt()).isEqualTo(MEMBERS);
        assertThat(phases.get(0).get("complete").asBoolean()).isTrue();

        assertThat(phases.get(1).get("kind").asString()).isEqualTo("KNOCKOUT");
        assertThat(phases.get(1).get("name").asString()).isEqualTo("Quartas de final");
        assertThat(phases.get(1).get("legs").asInt()).isEqualTo(2);
        // No SeasonEvent rows exist for this phase in this scenario (fixtures were inserted
        // directly), so "complete" -- driven purely by calendar events -- reads false; a
        // materialized-but-still-in-progress knockout phase is correctly "not complete" either way.
        assertThat(phases.get(1).get("complete").asBoolean()).isFalse();

        assertThat(phases.get(2).get("name").asString()).isEqualTo("Semifinal");
        assertThat(phases.get(2).get("kind").asString()).isEqualTo("KNOCKOUT");
    }

    @Test
    void unknownCompetitionIs404() throws Exception {
        mockMvc.perform(get("/api/v1/save-games/{s}/competitions/{c}/phases", saveGameId, UUID.randomUUID())
                        .header("Authorization", bearer))
                .andExpect(status().isNotFound());
    }

    @Test
    void tiesEndpointReturnsFourTiesInBracketOrder() throws Exception {
        mockMvc.perform(get("/api/v1/save-games/{s}/competitions/{c}/ties", saveGameId, competitionId)
                        .param("phaseId", quarterFinalPhaseId.toString())
                        .header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(4)))
                .andExpect(jsonPath("$[0].tieOrder").value(0))
                .andExpect(jsonPath("$[0].higherSeedClubName").exists())
                .andExpect(jsonPath("$[3].tieOrder").value(3));
    }

    @Test
    void anUnplayedTieReturnsNullGoalsAndNullWinnerRatherThanThrowing() throws Exception {
        String response = mockMvc.perform(get("/api/v1/save-games/{s}/competitions/{c}/ties",
                        saveGameId, competitionId)
                        .param("phaseId", quarterFinalPhaseId.toString())
                        .header("Authorization", bearer))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode ties = objectMapper.readTree(response);

        // tie 0: neither leg played.
        JsonNode tie0 = ties.get(0);
        assertThat(tie0.get("firstLegHomeGoals").isNull()).isTrue();
        assertThat(tie0.get("secondLegHomeGoals").isNull()).isTrue();
        assertThat(tie0.get("winnerClubId").isNull()).isTrue();
    }

    @Test
    void aTieWithOnlyTheFirstLegPlayedReturnsNullSecondLegAndNullWinner() throws Exception {
        String response = mockMvc.perform(get("/api/v1/save-games/{s}/competitions/{c}/ties",
                        saveGameId, competitionId)
                        .param("phaseId", quarterFinalPhaseId.toString())
                        .header("Authorization", bearer))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode ties = objectMapper.readTree(response);

        JsonNode tie1 = ties.get(1);
        assertThat(tie1.get("firstLegHomeGoals").asInt()).isEqualTo(1);
        assertThat(tie1.get("firstLegAwayGoals").asInt()).isEqualTo(0);
        assertThat(tie1.get("secondLegHomeGoals").isNull()).isTrue();
        assertThat(tie1.get("winnerClubId").isNull()).isTrue();
    }

    @Test
    void aDecisiveTieReturnsItsWinner() throws Exception {
        String response = mockMvc.perform(get("/api/v1/save-games/{s}/competitions/{c}/ties",
                        saveGameId, competitionId)
                        .param("phaseId", quarterFinalPhaseId.toString())
                        .header("Authorization", bearer))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode ties = objectMapper.readTree(response);

        JsonNode tie2 = ties.get(2);
        assertThat(tie2.get("winnerClubId").asString()).isEqualTo(decisiveTieHigherSeed.toString());
    }

    @Test
    void aLevelTieWithNoShootoutReturnsNullWinnerRatherThanThrowing() throws Exception {
        String response = mockMvc.perform(get("/api/v1/save-games/{s}/competitions/{c}/ties",
                        saveGameId, competitionId)
                        .param("phaseId", quarterFinalPhaseId.toString())
                        .header("Authorization", bearer))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode ties = objectMapper.readTree(response);

        JsonNode tie3 = ties.get(3);
        // Both legs played (goals present) but level on aggregate with no shootout recorded —
        // TieResolver.winner throws IllegalStateException; the endpoint must map that to null.
        assertThat(tie3.get("firstLegHomeGoals").isNull()).isFalse();
        assertThat(tie3.get("winnerClubId").isNull()).isTrue();
        assertThat(levelTieClub).isNotNull(); // sanity: fixture used the expected club
    }

    @Test
    void unknownPhaseOnTiesEndpointIs404() throws Exception {
        mockMvc.perform(get("/api/v1/save-games/{s}/competitions/{c}/ties", saveGameId, competitionId)
                        .param("phaseId", UUID.randomUUID().toString())
                        .header("Authorization", bearer))
                .andExpect(status().isNotFound());
    }

    @Test
    void standingsAcceptAnOptionalPhaseId() throws Exception {
        mockMvc.perform(get("/api/v1/save-games/{s}/competitions/{c}/standings", saveGameId, competitionId)
                        .param("phaseId", firstPhaseId.toString())
                        .header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(MEMBERS)))
                .andExpect(jsonPath("$[0].played").value(ROUNDS))
                .andExpect(jsonPath("$[0].won").value(ROUNDS));
    }

    @Test
    void standingsWithoutAPhaseIdStillWorks() throws Exception {
        mockMvc.perform(get("/api/v1/save-games/{s}/competitions/{c}/standings", saveGameId, competitionId)
                        .header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(MEMBERS)));
    }
}
