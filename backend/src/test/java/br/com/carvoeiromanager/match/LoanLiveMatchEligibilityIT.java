package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.common.ApiException;
import br.com.carvoeiromanager.common.ErrorCode;
import br.com.carvoeiromanager.competition.Fixture;
import br.com.carvoeiromanager.competition.FixtureRepository;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRepository;
import br.com.carvoeiromanager.player.Position;
import br.com.carvoeiromanager.player.RoleArchetype;
import br.com.carvoeiromanager.season.SeasonEvent;
import br.com.carvoeiromanager.season.SeasonEventRepository;
import br.com.carvoeiromanager.season.SeasonEventType;
import br.com.carvoeiromanager.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The loan fixture-release rule inside a <b>live</b> match — the one the manager watches minute by
 * minute, and therefore the one where getting it wrong is impossible to miss.
 *
 * <p>{@code bestXi}/{@code startingXi} were made opponent-aware by the loans slice, but a bench is
 * a list of players who are about to come on, and both benches were built with no opponent at all.
 * That left two ways onto the pitch for a player barred from starting:
 * <ul>
 *   <li><b>The AI's bench.</b> {@code LiveMatchEngine.maybeAiSubstitution} consumes the bench
 *       stored at kickoff and re-checks nothing, so a player correctly kept out of the AI's XI
 *       could still be brought on against the club that owns him.</li>
 *   <li><b>The manager's own bench</b>, when he is the borrower: {@code substitute} validated the
 *       incoming player against bench membership alone.</li>
 * </ul>
 *
 * <p>Both directions are covered here, each with a positive control in which the loan negotiated
 * the release — without it, a rule that barred everybody would pass just as well.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class LoanLiveMatchEligibilityIT {

    @Autowired LiveMatchService liveMatchService;
    @Autowired LiveMatchRepository liveMatchRepository;
    @Autowired LineupService lineupService;
    @Autowired SaveGameService saveGameService;
    @Autowired UserRepository userRepository;
    @Autowired PlayerRepository playerRepository;
    @Autowired FixtureRepository fixtureRepository;
    @Autowired SeasonEventRepository seasonEventRepository;

    private record Career(UUID userId, UUID saveGameId, UUID managedClubId, UUID opponentClubId) { }

    /**
     * A fresh career, plus the opponent of the very next managed fixture — which is the club a loan
     * has to involve for the fixture-release rule to have anything to say. Both scenarios below
     * arrange the loan across exactly that pairing.
     */
    private Career career(String prefix) {
        User user = User.create(prefix + "_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        SaveGameResponse save = saveGameService.createCareer(user.getId());

        SeasonEvent next = seasonEventRepository.findNextManagedEvent(save.id(), save.managedClubId());
        assertThat(next).isNotNull();
        assertThat(next.getType())
                .as("the fixture-release rule only has meaning against a scheduled opponent")
                .isEqualTo(SeasonEventType.MATCH);
        Fixture fixture = fixtureRepository
                .findBySaveGameIdAndCompetitionIdAndRound(save.id(), next.getCompetitionId(), next.getRound())
                .stream()
                .filter(f -> save.managedClubId().equals(f.getHomeClubId())
                        || save.managedClubId().equals(f.getAwayClubId()))
                .findFirst().orElseThrow();
        UUID opponent = save.managedClubId().equals(fixture.getHomeClubId())
                ? fixture.getAwayClubId()
                : fixture.getHomeClubId();
        return new Career(user.getId(), save.id(), save.managedClubId(), opponent);
    }

    /**
     * Lends a managed player to the club the managed side is about to play. His overall is set to 99
     * so that, whenever eligibility does not bar him, he is the borrower's unambiguous best player
     * and its automatic selection is certain to want him — making his absence attributable to the
     * loan rule and nothing else.
     */
    private Player lendToTheOpponent(Career c, boolean mayFaceOwner) {
        Player lent = playerRepository.findByClubIdAndYouthProspectFalse(c.managedClubId()).stream()
                .filter(p -> p.getPosition() != Position.GK)
                .min(Comparator.comparing(Player::getId)).orElseThrow();
        lent.setOverall(99);
        lent.setContractYears(3);
        lent.loanTo(c.opponentClubId(), 50, mayFaceOwner);
        playerRepository.save(lent);
        return playerRepository.findById(lent.getId()).orElseThrow();
    }

    /** The AI side's stored XI + bench, as serialized at kickoff. */
    private String aiSquadJson(Career c) {
        LiveMatch lm = liveMatchRepository.findBySaveGameId(c.saveGameId()).orElseThrow();
        return lm.isManagedIsHome()
                ? lm.getAwayXi() + lm.getAwayBench()
                : lm.getHomeXi() + lm.getHomeBench();
    }

    private LiveMatchState playToTheEnd(Career c) {
        LiveMatchState state = liveMatchService.getLive(c.saveGameId(), c.userId()).orElseThrow();
        for (int i = 0; i < 300 && !"FULL_TIME".equals(state.status()); i++) {
            state = liveMatchService.advance(c.saveGameId(), c.userId());
        }
        return state;
    }

    @Test
    void theAiNeverBenchesOrSubstitutesInAPlayerBarredFromFacingHisOwner() {
        Career c = career("llAiBar");
        Player lent = lendToTheOpponent(c, false);

        liveMatchService.kickoff(c.saveGameId(), c.userId());

        assertThat(aiSquadJson(c))
                .as("a player barred from this fixture belongs in neither the AI's XI nor its bench "
                        + "— the bench is where the AI substitutes from, with no second check")
                .doesNotContain(lent.getId().toString());

        LiveMatchState end = playToTheEnd(c);
        assertThat(end.status()).isEqualTo("FULL_TIME");
        assertThat(end.events())
                .as("the match must really have been played, or the absence below is vacuous")
                .isNotEmpty();
        assertThat(end.events())
                .as("no substitution, at any minute, may bring him on against the club that owns him")
                .noneMatch(e -> "SUBSTITUTION".equals(e.type())
                        && lent.getId().toString().equals(e.playerInId()));
    }

    /** The control: with the release negotiated, the same player is exactly the AI's best asset. */
    @Test
    void theAiFieldsALoanedPlayerWhoseReleaseWasNegotiated() {
        Career c = career("llAiRel");
        Player lent = lendToTheOpponent(c, true);

        liveMatchService.kickoff(c.saveGameId(), c.userId());

        assertThat(aiSquadJson(c))
                .as("nothing bars him, and at overall 99 the borrower's selection cannot pass him over")
                .contains(lent.getId().toString());
    }

    /**
     * Borrows a player from the club the managed side is about to play, and puts him on the saved
     * bench — the whole point is to have him sitting there, available to a hand-picked
     * substitution.
     */
    private Player borrowFromTheOpponentOntoTheBench(Career c, boolean mayFaceOwner) {
        Player borrowed = playerRepository.findByClubIdAndYouthProspectFalse(c.opponentClubId()).stream()
                .filter(p -> p.getPosition() != Position.GK)
                .min(Comparator.comparing(Player::getId)).orElseThrow();
        // The XI is settled BEFORE he arrives, so he cannot be in it whatever his position or his
        // rating. Merely dropping his overall does not keep him out: fillOutfieldSlots is
        // natural-first, and a natural for a slot the managed squad cannot fill naturally starts
        // however badly he is rated — which squad generation turns into a per-career coin flip.
        List<Player> xi = lineupService.bestXi(c.managedClubId(), null, null);
        assertThat(xi).as("a real XI, or the bench built from it proves nothing").hasSize(11);

        borrowed.setOverall(30);
        borrowed.setContractYears(3);
        borrowed.loanTo(c.managedClubId(), 50, mayFaceOwner);
        playerRepository.save(borrowed);
        List<LineupStarter> starters = xi.stream()
                .map(p -> new LineupStarter(p.getId(), RoleArchetype.defaultFor(p.getDetailedPosition()).name()))
                .toList();

        List<UUID> xiIds = xi.stream().map(Player::getId).toList();
        List<LineupStarter> bench = new ArrayList<>();
        bench.add(new LineupStarter(borrowed.getId(),
                RoleArchetype.defaultFor(borrowed.getDetailedPosition()).name()));
        playerRepository.findByClubIdAndYouthProspectFalse(c.managedClubId()).stream()
                .filter(p -> !xiIds.contains(p.getId()) && !p.getId().equals(borrowed.getId()))
                .filter(Player::isEligible)
                .sorted(Comparator.comparing(Player::getId))
                .limit(2)
                .forEach(p -> bench.add(new LineupStarter(p.getId(),
                        RoleArchetype.defaultFor(p.getDetailedPosition()).name())));

        lineupService.setLineup(c.saveGameId(), c.managedClubId(),
                new SetLineupRequest("4-4-2", starters, bench), c.userId());
        return playerRepository.findById(borrowed.getId()).orElseThrow();
    }

    @Test
    void theManagerCannotSubstituteInABorrowedPlayerAgainstTheClubThatOwnsHim() {
        Career c = career("llMgrBar");
        Player borrowed = borrowFromTheOpponentOntoTheBench(c, false);

        LiveMatchState start = liveMatchService.kickoff(c.saveGameId(), c.userId());

        assertThat(start.bench()).extracting(LivePlayerResponse::playerId)
                .as("he was saved onto the bench, but this fixture is against his owner")
                .doesNotContain(borrowed.getId().toString());
        assertThat(start.bench())
                .as("the rest of the saved bench must survive, or the exclusion above proves nothing")
                .isNotEmpty();

        UUID out = UUID.fromString(start.onPitch().get(6).playerId());
        assertThatThrownBy(() -> liveMatchService.substitute(c.saveGameId(), c.userId(),
                new SubstituteBatchRequest(List.of(new SubstituteRequest(out, borrowed.getId())))))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo(ErrorCode.INVALID_SUBSTITUTION));

        LiveMatchState reread = liveMatchService.getLive(c.saveGameId(), c.userId()).orElseThrow();
        assertThat(reread.subsRemaining()).isEqualTo(LiveMatchService.MAX_SUBS);
        assertThat(reread.events()).noneMatch(e -> "SUBSTITUTION".equals(e.type()));
    }

    /** The control: the same borrowed player, released for the fixture, comes on normally. */
    @Test
    void aBorrowedPlayerReleasedForTheFixtureCanBeSubstitutedIn() {
        Career c = career("llMgrRel");
        Player borrowed = borrowFromTheOpponentOntoTheBench(c, true);

        LiveMatchState start = liveMatchService.kickoff(c.saveGameId(), c.userId());

        assertThat(start.bench()).extracting(LivePlayerResponse::playerId)
                .contains(borrowed.getId().toString());

        UUID out = UUID.fromString(start.onPitch().get(6).playerId());
        LiveMatchState after = liveMatchService.substitute(c.saveGameId(), c.userId(),
                new SubstituteBatchRequest(List.of(new SubstituteRequest(out, borrowed.getId()))));

        assertThat(after.subsRemaining()).isEqualTo(LiveMatchService.MAX_SUBS - 1);
    }
}
