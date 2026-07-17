package br.com.carvoeiromanager.career;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.club.ClubRepository;
import br.com.carvoeiromanager.competition.CompetitionRepository;
import br.com.carvoeiromanager.competition.FixtureRepository;
import br.com.carvoeiromanager.player.PlayerRepository;
import br.com.carvoeiromanager.support.PostgresTestContainer;
import br.com.carvoeiromanager.seed.SeedCareer;
import br.com.carvoeiromanager.seed.SeedClub;
import br.com.carvoeiromanager.seed.SeedLoader;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class SaveGameServiceIT {

    @Autowired
    SaveGameService saveGameService;

    @Autowired
    UserRepository userRepository;

    @Autowired
    SaveGameRepository saveGameRepository;

    @Autowired
    ClubRepository clubRepository;

    @Autowired
    PlayerRepository playerRepository;

    @Autowired
    CompetitionRepository competitionRepository;

    @Autowired
    FixtureRepository fixtureRepository;

    @Test
    void creatingCareerSeedsClubsPlayersAndFullFixtureList() {
        // Given a persisted user
        User user = User.create("tigre_career", "hash");
        userRepository.save(user);
        UUID userId = user.getId();

        // When createCareer is called
        SaveGameResponse response = saveGameService.createCareer(userId);

        // Then a SaveGame was created with correct fields
        assertThat(response.id()).isNotNull();
        assertThat(response.seasonYear()).isGreaterThan(0);
        assertThat(response.competitionId()).isNotNull();
        assertThat(response.competitionName()).isNotBlank();
        assertThat(response.managedClubId()).isNotNull();
        assertThat(response.managedClubName()).isNotBlank();
        assertThat(response.currentRound()).isEqualTo(0);
        assertThat(response.totalRounds()).isGreaterThan(0);

        UUID saveGameId = response.id();

        // SaveGame persisted in DB
        assertThat(saveGameRepository.findById(saveGameId)).isPresent();

        // All clubs from the pool are created (28 clubs: 20 Série B + 8 Catarinense-only, CRI/AVA in both)
        var clubs = clubRepository.findBySaveGameId(saveGameId);
        assertThat(clubs).hasSize(28);

        // Every club has at least one player
        clubs.forEach(club ->
                assertThat(playerRepository.findByClubId(club.getId()))
                        .as("Club %s should have players", club.getName())
                        .isNotEmpty()
        );

        // Managed club is Criciúma (short name "CRI" maps to the club's full name from seed)
        assertThat(response.managedClubName()).contains("Criciúma");

        // Competition: 2 competitions now (Catarinense + Série B)
        var competitions = competitionRepository.findAll().stream()
                .filter(c -> c.getSaveGameId().equals(saveGameId))
                .toList();
        assertThat(competitions).hasSize(2);
        var catarinense = competitions.stream()
                .filter(c -> c.getName().contains("Catarinense"))
                .findFirst().orElseThrow();
        int catarinenseClubCount = 10;
        assertThat(catarinense.getTotalRounds()).isEqualTo(2 * (catarinenseClubCount - 1));

        // Fixtures for Catarinense: 10 * 9 = 90 fixtures, all unplayed
        var allFixtures = fixtureRepository.findBySaveGameIdAndCompetitionId(saveGameId, catarinense.getId());
        int expectedFixtures = catarinenseClubCount * (catarinenseClubCount - 1); // 90 for 10 clubs
        assertThat(allFixtures).hasSize(expectedFixtures);
        assertThat(allFixtures).allMatch(f -> !f.isPlayed());
        assertThat(allFixtures).allMatch(f -> f.getHomeGoals() == null);
        assertThat(allFixtures).allMatch(f -> f.getAwayGoals() == null);
    }

    @Test
    void creatingCareerSeedsTheRealFreeAgentPool() {
        // Given a persisted user
        User user = User.create("tigre_fa_" + UUID.randomUUID(), "hash");
        userRepository.save(user);

        // When a career is created
        SaveGameResponse response = saveGameService.createCareer(user.getId());

        // Then the free-agent pool is seeded 1:1 from the real seed file — no
        // procedural generation, no wage, no contract.
        var freeAgentSeeds = new SeedLoader().loadFreeAgents();
        var freeAgents = playerRepository.findBySaveGameIdAndClubIdIsNull(response.id());
        assertThat(freeAgents).hasSize(freeAgentSeeds.size());
        assertThat(freeAgents).allSatisfy(p ->
                assertThat(p.getWage()).isEqualByComparingTo(java.math.BigDecimal.ZERO));
        assertThat(freeAgents).allSatisfy(p -> assertThat(p.getContractYears()).isZero());

        // More than one South American nationality is represented — this is a
        // real multi-country pool, not the old Brasil-only procedural generator.
        var nationalities = freeAgents.stream()
                .map(br.com.carvoeiromanager.player.Player::getNationality)
                .collect(Collectors.toSet());
        assertThat(nationalities)
                .as("free-agent pool should span more than one nationality")
                .hasSizeGreaterThan(1);
    }

    @Test
    void criciumaPlayersGetAuthoredAttributesThroughCareerCreation() {
        // Given a persisted user
        User user = User.create("tigre_attrs", "hash");
        userRepository.save(user);

        // When a career is created (managed club is Criciúma)
        SaveGameResponse response = saveGameService.createCareer(user.getId());

        // Then Criciúma's Otero carries the hand-authored ("real") attributes, not derived values.
        var otero = playerRepository.findByClubId(response.managedClubId()).stream()
                .filter(p -> p.getName().equals("Otero"))
                .findFirst().orElseThrow();
        assertThat(otero.attributes().get(br.com.carvoeiromanager.player.Attribute.BOLA_PARADA)).isEqualTo(88);
        assertThat(otero.attributes().get(br.com.carvoeiromanager.player.Attribute.CHUTE_LONGE)).isEqualTo(82);
        assertThat(otero.attributes().get(br.com.carvoeiromanager.player.Attribute.VISAO)).isEqualTo(80);
        // Anchors are untouched (passing 80 → PASSE), so overall/strength are unchanged.
        assertThat(otero.attributes().get(br.com.carvoeiromanager.player.Attribute.PASSE)).isEqualTo(80);
    }

    @Test
    void newClubPlayersGetAuthoredAttributesThroughCareerCreation() {
        // Given a persisted user
        User user = User.create("tigre_nau", "hash");
        userRepository.save(user);

        // When a career is created
        SaveGameResponse response = saveGameService.createCareer(user.getId());
        UUID saveGameId = response.id();

        // Then the newly added club Náutico (NAU) has a full, authored squad
        var nau = clubRepository.findBySaveGameId(saveGameId).stream()
                .filter(c -> c.getShortName().equals("NAU"))
                .findFirst().orElseThrow();
        var squad = playerRepository.findByClubId(nau.getId());
        assertThat(squad).isNotEmpty();
        assertThat(squad).allSatisfy(p ->
                assertThat(p.attributes()).as("%s attributes", p.getName()).isNotNull());

        // And its goalkeeper carries a real (authored) REFLEXOS value, not a low derived one
        var bestGoalkeeper = squad.stream()
                .filter(p -> p.getPosition() == br.com.carvoeiromanager.player.Position.GK)
                .max(java.util.Comparator.comparingInt(
                        p -> p.attributes().get(br.com.carvoeiromanager.player.Attribute.REFLEXOS)))
                .orElseThrow();
        assertThat(bestGoalkeeper.attributes().get(br.com.carvoeiromanager.player.Attribute.REFLEXOS))
                .isGreaterThanOrEqualTo(60);
    }

    @Test
    void catarinenseOnlyClubPlayersGetAFullAuthoredSquadThroughCareerCreation() {
        // Given a persisted user
        User user = User.create("tigre_fig", "hash");
        userRepository.save(user);

        // When a career is created
        SaveGameResponse response = saveGameService.createCareer(user.getId());
        UUID saveGameId = response.id();

        // Then the Catarinense-only club Figueirense (FIG) has a full-sized authored
        // squad (18-23 players), not the old thin 14-15 player placeholder roster.
        var fig = clubRepository.findBySaveGameId(saveGameId).stream()
                .filter(c -> c.getShortName().equals("FIG"))
                .findFirst().orElseThrow();
        var squad = playerRepository.findByClubId(fig.getId());
        assertThat(squad.size()).isBetween(18, 23);
        assertThat(squad).allSatisfy(p ->
                assertThat(p.attributes()).as("%s attributes", p.getName()).isNotNull());
    }

    @Test
    void creatingCareerSeedsClubFinancialProfilesAndPlayerWages() {
        // Given a persisted user
        User user = User.create("tigre_finance", "hash");
        userRepository.save(user);

        // When a career is created
        SaveGameResponse response = saveGameService.createCareer(user.getId());
        UUID saveGameId = response.id();

        // Then every club has a populated financial profile
        var clubs = clubRepository.findBySaveGameId(saveGameId);
        assertThat(clubs).isNotEmpty();
        clubs.forEach(club -> {
            assertThat(club.getReputation()).as("reputation").isGreaterThan(0);
            assertThat(club.getStadiumCapacity()).as("capacity").isGreaterThan(0);
            assertThat(club.getBalance()).as("balance").isGreaterThan(java.math.BigDecimal.ZERO);
            assertThat(club.getSponsorshipPerRound()).as("sponsorship").isGreaterThan(java.math.BigDecimal.ZERO);
            assertThat(club.getStadiumUpkeepPerRound()).as("upkeep").isGreaterThan(java.math.BigDecimal.ZERO);
        });

        // And every club player has a positive wage (free agents have wage=0 by design)
        var clubPlayers = playerRepository.findBySaveGameId(saveGameId).stream()
                .filter(p -> p.getClubId() != null).toList();
        assertThat(clubPlayers)
                .isNotEmpty()
                .allSatisfy(p -> assertThat(p.getWage()).isGreaterThan(java.math.BigDecimal.ZERO));
    }

    @Test
    void clubsCarryTheirSeedTacticalIdentity() {
        User user = User.create("tacid_" + UUID.randomUUID(), "hash");
        userRepository.save(user);

        SaveGameResponse response = saveGameService.createCareer(user.getId());

        var clubs = clubRepository.findBySaveGameId(response.id());
        assertThat(clubs).allSatisfy(club -> {
            assertThat(club.getDefaultFormation()).as("%s formation", club.getShortName()).isNotBlank();
            assertThat(club.getDefaultMentality()).as("%s mentality", club.getShortName()).isNotBlank();
            assertThat(club.getDefaultPressing()).as("%s pressing", club.getShortName()).isNotBlank();
            assertThat(club.getDefaultMarking()).as("%s marking", club.getShortName()).isNotBlank();
        });

        // Spot-check one Série B club and one Catarinense-only club against the
        // exact seed values they were loaded from.
        SeedCareer seed = new SeedLoader().loadCareer();
        var seedByShortName = seed.clubPool().stream()
                .collect(Collectors.toMap(SeedClub::shortName, sc -> sc));

        var nau = clubs.stream().filter(c -> c.getShortName().equals("NAU")).findFirst().orElseThrow();
        SeedClub seedNau = seedByShortName.get("NAU");
        assertThat(nau.getDefaultFormation()).isEqualTo(seedNau.formation());
        assertThat(nau.getDefaultMentality()).isEqualTo(seedNau.mentality());
        assertThat(nau.getDefaultPressing()).isEqualTo(seedNau.pressing());
        assertThat(nau.getDefaultMarking()).isEqualTo(seedNau.marking());

        var bar = clubs.stream().filter(c -> c.getShortName().equals("BAR")).findFirst().orElseThrow();
        SeedClub seedBar = seedByShortName.get("BAR");
        assertThat(bar.getDefaultFormation()).isEqualTo(seedBar.formation());
        assertThat(bar.getDefaultMentality()).isEqualTo(seedBar.mentality());
    }

    @Test
    void createCareerPersistsTheManagerIdentity() {
        User user = User.create("tigre_manager", "hash");
        userRepository.save(user);

        SaveGameResponse response = saveGameService.createCareer(user.getId(),
                new CreateSaveGameRequest("Filipe Silva", "Brasil"));

        assertThat(response.managerName()).isEqualTo("Filipe Silva");
        assertThat(response.managerNationality()).isEqualTo("Brasil");
    }

    @Test
    void theOneArgOverloadStillWorksForCallersThatDontCareAboutManagerIdentity() {
        User user = User.create("tigre_default", "hash");
        userRepository.save(user);

        SaveGameResponse response = saveGameService.createCareer(user.getId());

        assertThat(response.managerName()).isNotBlank();
        assertThat(response.managerNationality()).isNotBlank();
    }
}
