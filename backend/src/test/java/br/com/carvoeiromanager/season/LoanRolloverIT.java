package br.com.carvoeiromanager.season;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.career.SaveGameStatus;
import br.com.carvoeiromanager.career.SaveGameRepository;
import br.com.carvoeiromanager.club.Club;
import br.com.carvoeiromanager.club.ClubRepository;
import br.com.carvoeiromanager.common.SquadRules;
import br.com.carvoeiromanager.competition.Competition;
import br.com.carvoeiromanager.competition.CompetitionRepository;
import br.com.carvoeiromanager.competition.TopScorerRow;
import br.com.carvoeiromanager.competition.TopScorersService;
import br.com.carvoeiromanager.contract.PreContractAgreement;
import br.com.carvoeiromanager.contract.PreContractAgreementRepository;
import br.com.carvoeiromanager.loan.LoanOffer;
import br.com.carvoeiromanager.loan.LoanOfferRepository;
import br.com.carvoeiromanager.player.ListedReason;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRepository;
import br.com.carvoeiromanager.player.Position;
import br.com.carvoeiromanager.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bringing loans home at the rollover: the return must land AFTER the season archive (a loaned
 * player's goals are archived under the club he actually played for) and BEFORE the contract
 * tick (his contract is with his owner, not the club he was playing for).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class LoanRolloverIT {

    @Autowired UserRepository userRepository;
    @Autowired SaveGameService saveGameService;
    @Autowired SaveGameRepository saveGameRepository;
    @Autowired SeasonAdvanceService seasonAdvanceService;
    @Autowired SeasonRolloverService rolloverService;
    @Autowired PlayerRepository playerRepository;
    @Autowired PreContractAgreementRepository preContractAgreementRepository;
    @Autowired LoanOfferRepository loanOfferRepository;
    @Autowired ClubRepository clubRepository;
    @Autowired CompetitionRepository competitionRepository;
    @Autowired TopScorersService topScorersService;
    @Autowired SeasonRepository seasonRepository;
    @Autowired SeasonTopScorerRepository seasonTopScorerRepository;

    private record Career(UUID userId, SaveGameResponse save) {
        UUID id() { return save.id(); }
        UUID clubId() { return save.managedClubId(); }
    }

    /**
     * The probe player the test tracks across the rollover must survive it: rollover ages
     * everyone +1 and then rolls retirement (possible from age 35, certain at 38) — a retired
     * player is DELETED, so findById would throw. Age &lt;= 33 (34 after the tick) has zero
     * retirement probability. Youth prospects follow a separate develop/depart path, so skip
     * those too, and a player already holding a pre-contract deal moves to his new club instead
     * of lapsing, which would also derail the probe.
     */
    private Player probePlayer(UUID saveGameId, List<Player> squad) {
        Set<UUID> preContracted = preContractAgreementRepository.findBySaveGameId(saveGameId).stream()
                .map(PreContractAgreement::getPlayerId)
                .collect(Collectors.toSet());
        return squad.stream()
                .filter(p -> !p.isYouthProspect())
                .filter(p -> p.getAge() <= 33)
                .filter(p -> !preContracted.contains(p.getId()))
                .min(Comparator.comparing(Player::getId))
                .orElseThrow();
    }

    private Career playToSeasonEnd() {
        User user = User.create("loanroll_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        Career c = new Career(user.getId(), saveGameService.createCareer(user.getId()));
        int guard = 0;
        while (saveGameRepository.findById(c.id()).orElseThrow().getStatus() != SaveGameStatus.FINISHED
                && guard++ < 500) {
            seasonAdvanceService.advance(c.id(), c.userId());
        }
        return c;
    }

    /** A fresh filler professional, contracted for 5 years and young enough to never retire. */
    private Player filler(UUID saveGameId, UUID clubId, Position position, int overall) {
        Player p = Player.create(saveGameId, clubId, "Filler-" + UUID.randomUUID(), position,
                25, "Brasil", 60, 60, 60, 60, 60, 60, overall);
        p.setContractYears(5);
        return p;
    }

    @Test
    void aLoanedPlayerComesHomeAtTheRolloverWithNoLoanStateLeft() {
        Career c = playToSeasonEnd();
        List<Player> squad = playerRepository.findByClubId(c.clubId());
        squad.forEach(p -> p.setContractYears(5));       // keep everyone off the lapse path
        Player lent = probePlayer(c.id(), squad);
        UUID borrower = playerRepository.findBySaveGameId(c.id()).stream()
                .map(Player::getClubId)
                .filter(id -> id != null && !id.equals(c.clubId()))
                .findFirst().orElseThrow();
        playerRepository.saveAll(squad);
        lent.loanTo(borrower, 40, true);
        playerRepository.save(lent);

        // A loan_offer negotiated for this exact loan is season-scoped and must be purged at
        // rollover — asserted below, closing the gap on LoanOfferRepository.deleteBySaveGameId
        // (declared since Task 4, exercised by no test anywhere until now).
        LoanOffer offer = LoanOffer.create(c.id(), lent.getId(), c.clubId(), borrower, 40, true, 1);
        loanOfferRepository.save(offer);

        RolloverResponse r = rolloverService.rollover(c.id(), c.userId());

        Player home = playerRepository.findById(lent.getId()).orElseThrow();
        assertThat(home.isOnLoan()).isFalse();
        assertThat(home.getClubId()).isEqualTo(c.clubId());
        assertThat(home.getLoanWageShare()).isNull();
        assertThat(home.getLoanMayFaceOwner()).isNull();
        assertThat(r.loanReturns()).extracting(SquadChangeResponse::playerName)
                .contains(home.getName());

        assertThat(loanOfferRepository.findById(offer.getId())).isEmpty();
    }

    /**
     * The ordering guard: the archive (step 1) runs BEFORE the loan return (start of step 3), so
     * a scorer who is still on loan when rollover is invoked is archived under the club whose
     * shirt he was wearing — the borrower — not under the owner he is about to go home to.
     *
     * <p>Read {@link SeasonRolloverServiceIT#leadingClubbedScorer} for the shape of this
     * arrangement: pick the competition's leading scorer who still has a club, exactly as that
     * suite does for its own release-ordering test
     * ({@code aTopScorerReleasedBeforeRolloverIsArchivedUnderTheClubThatReleasedHim}). Here, the
     * scorer's real club becomes his owner and a second club becomes his borrower via
     * {@link Player#loanTo}, called after the season already finished — so his archived goals
     * must still name the club he played them for, not the one he is only nominally attached to
     * moving into next season.
     */
    @Test
    void aLoanedTopScorerIsArchivedUnderTheClubHePlayedFor() {
        Career c = playToSeasonEnd();
        Competition competition = competitionRepository.findBySaveGameId(c.id()).stream()
                .filter(comp -> comp.getName().contains("Catarinense"))
                .findFirst().orElseThrow();
        TopScorerRow top = topScorersService.getTopScorers(c.id(), competition.getId(), c.userId()).stream()
                .filter(row -> row.clubId() != null)
                .findFirst().orElseThrow();
        Player scorer = playerRepository.findById(top.playerId()).orElseThrow();
        UUID scoringClubId = scorer.getClubId();
        Club borrowerClub = clubRepository.findBySaveGameId(c.id()).stream()
                .filter(cl -> !cl.getId().equals(scoringClubId))
                .findFirst().orElseThrow();
        scorer.setContractYears(5); // keep him off the lapse path; irrelevant to the archive itself
        scorer.loanTo(borrowerClub.getId(), 40, true);
        playerRepository.save(scorer);

        rolloverService.rollover(c.id(), c.userId());

        Season catSeason = seasonRepository.findBySaveGameIdOrderBySeasonYearDesc(c.id()).stream()
                .filter(s -> s.getCompetitionName().contains("Catarinense"))
                .findFirst().orElseThrow();
        SeasonTopScorer archived = seasonTopScorerRepository
                .findBySeasonIdOrderByRank(catSeason.getId()).stream()
                .filter(row -> row.getPlayerId().equals(top.playerId()))
                .findFirst().orElseThrow();
        assertThat(archived.getClubId()).isEqualTo(borrowerClub.getId());
        assertThat(archived.getClubName()).isEqualTo(borrowerClub.getName());
        assertThat(archived.getClubId()).isNotEqualTo(scoringClubId);
        assertThat(archived.getGoals()).isEqualTo(top.goals());
        assertThat(archived.getPlayerName()).isEqualTo(top.playerName());
    }

    /**
     * Squad-cap overflow after several loans return at once: the cap is otherwise only checked
     * one signing at a time (see {@code AiTransferEngineIT}'s buy-side scenarios, whose squads
     * never exceed {@link SquadRules#MAX_SQUAD_SIZE} because each purchase is gated individually),
     * but a rollover can bring several loanees home together. {@code filler} below mirrors
     * {@code AiTransferEngineIT.filler}: {@link Player#create} with fixed, harmless attributes,
     * young enough (age 25) and contracted long enough (5 years) to never retire or lapse during
     * this rollover, so the only thing moving the managed squad's size is the arrangement itself.
     *
     * <p>The managed squad is padded to exactly the cap with players already at home, then three
     * more managed-owned players are created directly into the on-loan state (owner = managed
     * club, current club = the borrower) so they never counted toward that at-home total. Only
     * the loan-return pass at rollover brings them home, pushing the squad to cap + 3 — the
     * overflow the surplus-listing helper must resolve.
     */
    @Test
    void returnsThatOverflowTheSquadListTheWeakestPlayersAsSurplus() {
        Career c = playToSeasonEnd();
        List<Player> squad = playerRepository.findByClubIdAndYouthProspectFalse(c.clubId());
        squad.forEach(p -> {
            p.setContractYears(5);
            p.setAge(25); // neutralize retirement so only the arrangement moves squad size
        });
        playerRepository.saveAll(squad);
        UUID borrower = playerRepository.findBySaveGameId(c.id()).stream()
                .map(Player::getClubId)
                .filter(id -> id != null && !id.equals(c.clubId()))
                .findFirst().orElseThrow();

        // Pad the managed squad to exactly the cap with home-based fillers.
        int padCount = SquadRules.MAX_SQUAD_SIZE - squad.size();
        List<Player> padding = new ArrayList<>();
        for (int i = 0; i < padCount; i++) {
            padding.add(filler(c.id(), c.clubId(), Position.MID, 60));
        }
        playerRepository.saveAll(padding);
        assertThat(playerRepository.findByClubIdAndYouthProspectFalse(c.clubId()))
                .hasSize(SquadRules.MAX_SQUAD_SIZE);

        // Three MORE managed-owned players, created directly into the on-loan state: the three
        // returns push the squad from the cap to cap + 3, and that overflow must be listed.
        List<Player> loanedOut = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Player p = filler(c.id(), c.clubId(), Position.MID, 55);
            p.loanTo(borrower, 40, true);
            loanedOut.add(p);
        }
        playerRepository.saveAll(loanedOut);
        assertThat(playerRepository.findByClubIdAndYouthProspectFalse(c.clubId()))
                .hasSize(SquadRules.MAX_SQUAD_SIZE); // loanees don't count while they're away

        RolloverResponse r = rolloverService.rollover(c.id(), c.userId());

        List<Player> after = playerRepository.findByClubIdAndYouthProspectFalse(c.clubId());
        assertThat(after).hasSizeGreaterThan(SquadRules.MAX_SQUAD_SIZE);
        int overflow = after.size() - SquadRules.MAX_SQUAD_SIZE;

        // The rule under test is "lowest overall first, ties broken by id" — not just "some N
        // players get listed". Compute the expected set from the squad's actual overalls (the
        // pre-existing career squad's overalls are unpinned) instead of hardcoding which three,
        // so this fails if the comparator's direction or tie-break ever changes.
        Set<UUID> expectedListed = after.stream()
                .sorted(Comparator.comparingInt(Player::getOverall).thenComparing(Player::getId))
                .map(Player::getId)
                .limit(overflow)
                .collect(Collectors.toSet());
        Set<UUID> actualListed = after.stream()
                .filter(Player::isTransferListed)
                .map(Player::getId)
                .collect(Collectors.toSet());
        assertThat(actualListed).isEqualTo(expectedListed);
        assertThat(after.stream().filter(Player::isTransferListed))
                .allMatch(p -> p.getListedReason() == ListedReason.SURPLUS);
        assertThat(r.surplusListings()).isNotEmpty();
    }
}
