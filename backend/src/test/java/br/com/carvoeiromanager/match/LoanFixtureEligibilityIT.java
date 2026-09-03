package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGame;
import br.com.carvoeiromanager.career.SaveGameRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.competition.Competition;
import br.com.carvoeiromanager.competition.CompetitionRepository;
import br.com.carvoeiromanager.competition.Fixture;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRepository;
import br.com.carvoeiromanager.player.Position;
import br.com.carvoeiromanager.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Behavioural coverage for the loan fixture-release rule (see
 * {@link br.com.carvoeiromanager.loan.LoanEligibility}) at the actual {@link FixtureSimulator}
 * level, on the AI side specifically: a managed player lent to a club becomes that club's
 * AI-selected XI candidate (resolved via {@code LineupService.bestXi}, NOT the managed
 * {@code startingXi} path), so this is exactly the case a manager watching the fixture would see
 * and disbelieve if it were wrong.
 *
 * <p>Each test builds a fresh {@link Fixture} directly (managed club vs. the borrower club) rather
 * than hunting the seeded calendar for one, following {@code FixtureSimulatorExtraTimeIT}'s pattern
 * of driving a single fixture with {@code Fixture.create(...)} + {@code simulateRound}. The lent
 * player's overall is boosted to 99 so that, whenever he IS eligible to play, he is unambiguously
 * the strongest candidate at his position and {@code bestXi} is certain to pick him — making his
 * absence from the borrower's XI attributable only to the loan restriction, never to bad luck in
 * the rating race. Presence is verified through {@link FixtureAppearanceRepository}, which
 * {@code FixtureSimulator.persistAppearances} populates for every player in BOTH resolved XIs (not
 * just those who register a match event), so an appearance row is a direct, unambiguous witness of
 * "was picked for this fixture."
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class LoanFixtureEligibilityIT {

    @Autowired FixtureSimulator fixtureSimulator;
    @Autowired SaveGameService saveGameService;
    @Autowired SaveGameRepository saveGameRepository;
    @Autowired UserRepository userRepository;
    @Autowired CompetitionRepository competitionRepository;
    @Autowired PlayerRepository playerRepository;
    @Autowired FixtureAppearanceRepository fixtureAppearanceRepository;

    private record Scenario(SaveGame save, UUID saveGameId, UUID managedClubId, UUID competitionId,
                            UUID borrowerClubId, Player lentPlayer) {
    }

    /**
     * Creates a fresh career, picks an AI club to act as the borrower, and lends the managed
     * squad's strongest non-GK outfielder to it with the given release term. The player's overall
     * is boosted to 99 (bestXi ranks candidates by rating) so he is the unambiguous top pick at his
     * position whenever loan eligibility does not bar him.
     */
    private Scenario lendManagedPlayer(String userPrefix, boolean mayFaceOwner) {
        User user = User.create(userPrefix + "_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        SaveGameResponse response = saveGameService.createCareer(user.getId());
        UUID saveGameId = response.id();
        UUID managedClubId = response.managedClubId();
        SaveGame save = saveGameRepository.findById(saveGameId).orElseThrow();
        Competition competition = competitionRepository.findBySaveGameId(saveGameId).get(0);

        UUID borrowerClubId = playerRepository.findBySaveGameId(saveGameId).stream()
                .map(Player::getClubId)
                .filter(id -> id != null && !id.equals(managedClubId))
                .distinct().sorted().findFirst().orElseThrow();

        Player lentPlayer = playerRepository.findByClubIdAndYouthProspectFalse(managedClubId).stream()
                .filter(p -> p.getPosition() != Position.GK)
                .max(Comparator.comparingInt(Player::getOverall))
                .orElseThrow();
        lentPlayer.setOverall(99);
        lentPlayer.loanTo(borrowerClubId, 50, mayFaceOwner);
        playerRepository.save(lentPlayer);

        return new Scenario(save, saveGameId, managedClubId, competition.getId(), borrowerClubId, lentPlayer);
    }

    private List<FixtureAppearance> simulateManagedVsBorrower(Scenario s) {
        LocalDate matchDate = LocalDate.now();
        Fixture fixture = Fixture.create(s.saveGameId(), s.competitionId(), 901,
                s.managedClubId(), s.borrowerClubId(), matchDate);
        fixtureSimulator.simulateRound(s.save(), s.competitionId(), List.of(fixture));
        return fixtureAppearanceRepository.findByFixtureIdIn(List.of(fixture.getId()));
    }

    @Test
    void aLoanedPlayerWithNoReleaseIsBarredFromBothXisAgainstHisOwner() {
        Scenario s = lendManagedPlayer("loanfx_bar", false);

        List<FixtureAppearance> appearances = simulateManagedVsBorrower(s);

        assertThat(appearances)
                .as("the loaned player must not appear in either XI when facing his owner without a negotiated release")
                .noneMatch(a -> a.getPlayerId().equals(s.lentPlayer().getId()));
    }

    @Test
    void aLoanedPlayerWithTheNegotiatedReleaseMayFaceHisOwner() {
        Scenario s = lendManagedPlayer("loanfx_rel", true);

        List<FixtureAppearance> appearances = simulateManagedVsBorrower(s);

        assertThat(appearances)
                .as("the loan's negotiated release lets the AI-controlled borrower field him against his owner")
                .anyMatch(a -> a.getPlayerId().equals(s.lentPlayer().getId())
                        && a.getClubId().equals(s.borrowerClubId()));
    }
}
