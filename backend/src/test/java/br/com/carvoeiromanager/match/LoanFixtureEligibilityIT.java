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
import java.util.function.Predicate;
import java.util.stream.Collectors;

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
        return lendManagedPlayer(userPrefix, mayFaceOwner, p -> p.getPosition() != Position.GK);
    }

    /**
     * As above, but lends the strongest managed player matching {@code lendable} — used to lend a
     * goalkeeper, which is the reachable shape of the fallback bug: the borrowing club loses its
     * own keeper to injury and the barred one is the only other keeper on its books.
     */
    private Scenario lendManagedPlayer(String userPrefix, boolean mayFaceOwner,
                                       Predicate<Player> lendable) {
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
                .filter(lendable)
                .max(Comparator.comparingInt(Player::getOverall))
                .orElseThrow();
        lentPlayer.setOverall(99);
        lentPlayer.loanTo(borrowerClubId, 50, mayFaceOwner);
        playerRepository.save(lentPlayer);

        return new Scenario(save, saveGameId, managedClubId, competition.getId(), borrowerClubId, lentPlayer);
    }

    /**
     * The match is dated from the save's own clock, never {@code LocalDate.now()} — a fixture dated
     * off the wall clock makes the fitness-adjusted rating that {@code bestXi} ranks by depend on
     * the day the suite runs, which is exactly the determinism the plan forbids.
     */
    private List<FixtureAppearance> simulateManagedVsBorrower(Scenario s) {
        LocalDate matchDate = s.save().getCurrentDate();
        Fixture fixture = Fixture.create(s.saveGameId(), s.competitionId(), 901,
                s.managedClubId(), s.borrowerClubId(), matchDate);
        fixtureSimulator.simulateRound(s.save(), s.competitionId(), List.of(fixture));
        return fixtureAppearanceRepository.findByFixtureIdIn(List.of(fixture.getId()));
    }

    @Test
    void aLoanedPlayerWithNoReleaseIsBarredFromBothXisAgainstHisOwner() {
        Scenario s = lendManagedPlayer("loanfx_bar", false);

        List<FixtureAppearance> appearances = simulateManagedVsBorrower(s);

        // A noneMatch over an empty list passes for the wrong reason. The fixture was really played
        // and really attributed, so both XIs are on record: 22 appearance rows, none of them his.
        assertThat(appearances)
                .as("the fixture must actually have been simulated and attributed, or the "
                        + "absence asserted below would be vacuous")
                .isNotEmpty()
                .extracting(FixtureAppearance::getClubId)
                .contains(s.managedClubId(), s.borrowerClubId());
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

    // --- The bar must also bind bestXi's three fallbacks, not just its `eligible` filter ---

    /**
     * The reachable one. The borrower's own keepers are injured, so no keeper of his is eligible and
     * {@code bestXi} reaches its ineligible-GK fallback. That fallback used to read the club's whole
     * squad, which put the borrowed 99-rated keeper — barred from this very fixture — straight into
     * goal against the club that owns him. He must not play; an injured keeper of the borrower's own
     * goes in ahead of him, and the fixture is still simulated to completion.
     */
    @Test
    void aBarredLoanedKeeperDoesNotTakeTheGoalWhenTheBorrowersOwnKeepersAreInjured() {
        Scenario s = lendManagedPlayer("loanfx_gkinj", false, p -> p.getPosition() == Position.GK);
        List<Player> ownKeepers = borrowerOwnPlayers(s).stream()
                .filter(p -> p.getPosition() == Position.GK)
                .collect(Collectors.toList());
        assertThat(ownKeepers).as("the borrower must own keepers of its own for this scenario").isNotEmpty();
        ownKeepers.forEach(p -> p.addInjury(3));
        playerRepository.saveAll(ownKeepers);

        List<FixtureAppearance> appearances = simulateManagedVsBorrower(s);

        assertThat(appearances)
                .as("the fixture must actually have been simulated rather than thrown out of")
                .isNotEmpty()
                .extracting(FixtureAppearance::getClubId)
                .contains(s.managedClubId(), s.borrowerClubId());
        List<UUID> borrowerXi = xiOf(appearances, s.borrowerClubId());
        assertThat(borrowerXi)
                .as("the ineligible-GK fallback must not reach past the loan bar for the borrowed keeper")
                .doesNotContain(s.lentPlayer().getId());
        assertThat(borrowerXi)
                .as("an injured keeper of the borrower's own takes the goal instead")
                .containsAnyElementsOf(ownKeepers.stream().map(Player::getId).toList());
    }

    /**
     * The harder half of the same case: the borrower has no other keeper at all, so there is no
     * eligible alternative AND no ineligible one. {@code improvisedKeeper} must put an outfielder in
     * goal rather than reach for the barred keeper — and must not throw {@code NO_GK_FOUND}, which
     * would brick the save exactly as it did before that method existed.
     */
    @Test
    void aBarredLoanedKeeperIsImprovisedAroundWhenTheBorrowerHasNoKeeperOfHisOwn() {
        Scenario s = lendManagedPlayer("loanfx_gkonly", false, p -> p.getPosition() == Position.GK);
        List<Player> ownKeepers = borrowerOwnPlayers(s).stream()
                .filter(p -> p.getPosition() == Position.GK)
                .collect(Collectors.toList());
        assertThat(ownKeepers).isNotEmpty();
        // Contracts lapsed at rollover: they are free agents, so the borrowed keeper is the only
        // goalkeeper left on the club's books — and he may not face the club that owns him.
        ownKeepers.forEach(p -> p.setClubId(null));
        playerRepository.saveAll(ownKeepers);

        List<FixtureAppearance> appearances = simulateManagedVsBorrower(s);

        assertThat(appearances)
                .as("the fixture must still play to completion, not fail with NO_GK_FOUND")
                .isNotEmpty()
                .extracting(FixtureAppearance::getClubId)
                .contains(s.managedClubId(), s.borrowerClubId());
        List<UUID> borrowerXi = xiOf(appearances, s.borrowerClubId());
        assertThat(borrowerXi).isNotEmpty();
        assertThat(borrowerXi)
                .as("improvising in goal must not mean reaching for the one keeper the loan bars")
                .doesNotContain(s.lentPlayer().getId());
        assertThat(positionsOf(borrowerXi))
                .as("the borrower improvises an outfielder in goal instead")
                .doesNotContain(Position.GK);
    }

    /**
     * The outfield top-up. The borrower is cut to five eligible outfielders and has no ineligible
     * ones to top up from, so the only candidate left for the remaining five slots is the barred
     * loanee. The bar wins: the borrower fields a SHORT XI (keeper + five) and the match is still
     * played. Fielding eleven is a convenience; the fixture-release rule is a rule of the
     * competition.
     */
    @Test
    void aBarredLoanedOutfielderLeavesTheBorrowerShortRatherThanFacingHisOwner() {
        Scenario s = lendManagedPlayer("loanfx_short", false);
        List<Player> outfielders = borrowerOwnPlayers(s).stream()
                .filter(p -> p.getPosition() != Position.GK)
                .sorted(Comparator.comparing(Player::getId))
                .collect(Collectors.toList());
        List<Player> kept = outfielders.stream().filter(Player::isEligible).limit(5).toList();
        assertThat(kept).as("the borrower needs at least five eligible outfielders to keep").hasSize(5);
        List<Player> released = outfielders.stream()
                .filter(p -> kept.stream().noneMatch(k -> k.getId().equals(p.getId())))
                .collect(Collectors.toList());
        released.forEach(p -> p.setClubId(null));
        playerRepository.saveAll(released);

        List<FixtureAppearance> appearances = simulateManagedVsBorrower(s);

        assertThat(appearances)
                .as("the fixture must still play to completion with a short XI")
                .isNotEmpty()
                .extracting(FixtureAppearance::getClubId)
                .contains(s.managedClubId(), s.borrowerClubId());
        List<UUID> borrowerXi = xiOf(appearances, s.borrowerClubId());
        assertThat(borrowerXi)
                .as("the ineligible top-up must not reach past the loan bar")
                .doesNotContain(s.lentPlayer().getId());
        assertThat(borrowerXi)
                .as("one keeper plus the five eligible outfielders — short, and that is correct")
                .hasSize(6);
        assertThat(xiOf(appearances, s.managedClubId()))
                .as("the owner is unaffected and fields a full eleven")
                .hasSize(11);
    }

    /** Everyone at the borrowing club except the player lent to them. */
    private List<Player> borrowerOwnPlayers(Scenario s) {
        return playerRepository.findByClubIdAndYouthProspectFalse(s.borrowerClubId()).stream()
                .filter(p -> !p.getId().equals(s.lentPlayer().getId()))
                .collect(Collectors.toList());
    }

    /** The XI one club actually fielded, read back from the attributed appearances. */
    private List<UUID> xiOf(List<FixtureAppearance> appearances, UUID clubId) {
        return appearances.stream()
                .filter(a -> a.getClubId().equals(clubId))
                .map(FixtureAppearance::getPlayerId)
                .toList();
    }

    private List<Position> positionsOf(List<UUID> playerIds) {
        return playerIds.stream()
                .map(id -> playerRepository.findById(id).orElseThrow().getPosition())
                .toList();
    }
}
