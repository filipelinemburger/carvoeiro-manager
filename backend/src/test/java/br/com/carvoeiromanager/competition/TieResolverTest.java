package br.com.carvoeiromanager.competition;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure unit-level coverage for {@link TieResolver}. No Spring, no database — {@link Fixture} rows
 * are built directly via {@link Fixture#create} with only the knockout columns
 * ({@code tieId}/{@code leg}/{@code tieOrder}/penalties) that {@link TieResolver} reads.
 *
 * <p>Convention pinned by {@link PhaseResolver}: the higher seed hosts the second leg, so
 * {@code secondLeg.getHomeClubId()} is always the higher seed and {@code firstLeg.getHomeClubId()}
 * is always the lower seed.
 */
class TieResolverTest {

    private static final UUID SAVE_GAME_ID = UUID.randomUUID();
    private static final UUID COMPETITION_ID = UUID.randomUUID();
    private static final LocalDate DATE = LocalDate.of(2025, 6, 1);

    private final UUID clubA = UUID.randomUUID();
    private final UUID clubB = UUID.randomUUID();

    /**
     * Builds a two-legged tie where {@code clubA} hosts the first leg and {@code clubB} — the
     * higher seed — hosts the second, matching the score lines literally (e.g. "Leg 1: A 2-1 B"
     * is {@code leg1HomeGoals=2, leg1AwayGoals=1}).
     */
    private KnockoutTie tie(UUID lowerSeed, UUID higherSeed, int leg1HomeGoals, int leg1AwayGoals,
                            int leg2HomeGoals, int leg2AwayGoals) {
        UUID tieId = UUID.randomUUID();

        Fixture leg1 = Fixture.create(SAVE_GAME_ID, COMPETITION_ID, 1, lowerSeed, higherSeed, DATE);
        leg1.recordResult(leg1HomeGoals, leg1AwayGoals);
        leg1.setLeg(1);
        leg1.setTieId(tieId);
        leg1.setTieOrder(0);

        Fixture leg2 = Fixture.create(SAVE_GAME_ID, COMPETITION_ID, 2, higherSeed, lowerSeed, DATE);
        leg2.recordResult(leg2HomeGoals, leg2AwayGoals);
        leg2.setLeg(2);
        leg2.setTieId(tieId);
        leg2.setTieOrder(0);

        return new KnockoutTie(tieId, higherSeed, lowerSeed, leg1, leg2);
    }

    private KnockoutTie tieWithPenalties(UUID lowerSeed, UUID higherSeed, int leg1HomeGoals, int leg1AwayGoals,
                                         int leg2HomeGoals, int leg2AwayGoals, int penaltyHome, int penaltyAway) {
        KnockoutTie base = tie(lowerSeed, higherSeed, leg1HomeGoals, leg1AwayGoals, leg2HomeGoals, leg2AwayGoals);
        base.secondLeg().setPenaltyHome(penaltyHome);
        base.secondLeg().setPenaltyAway(penaltyAway);
        return base;
    }

    @Test
    void theAggregateWinnerAdvances() {
        // Leg 1: A 2-1 B. Leg 2: B 1-1 A. Aggregate A 3-2.
        assertThat(TieResolver.winner(tie(clubA, clubB, 2, 1, 1, 1))).isEqualTo(clubA);
    }

    @Test
    void awayGoalsDoNotBreakALevelAggregate() {
        // Leg 1: A 0-0 B. Leg 2: B 1-1 A. Aggregate 1-1; A scored away, B did not.
        // Without an away-goals rule this must go to penalties, not to A — and the shootout
        // itself (home/clubB 4, away/clubA 2) is what hands it to clubB, not the away goal.
        KnockoutTie level = tieWithPenalties(clubA, clubB, 0, 0, 1, 1, /* pens */ 4, 2);
        assertThat(TieResolver.winner(level)).isEqualTo(clubB);
    }

    @Test
    void penaltiesDecideALevelAggregate() {
        assertThat(TieResolver.winner(tieWithPenalties(clubA, clubB, 1, 1, 2, 2, 5, 3)))
                .isEqualTo(clubB);   // clubB was the leg-2 home side and won the shootout 5-3
    }

    @Test
    void anUnplayedTieHasNoWinner() {
        UUID tieId = UUID.randomUUID();
        Fixture leg1 = Fixture.create(SAVE_GAME_ID, COMPETITION_ID, 1, clubA, clubB, DATE);
        leg1.setLeg(1);
        leg1.setTieId(tieId);
        Fixture leg2 = Fixture.create(SAVE_GAME_ID, COMPETITION_ID, 2, clubB, clubA, DATE);
        leg2.setLeg(2);
        leg2.setTieId(tieId);
        KnockoutTie unplayedTie = new KnockoutTie(tieId, clubB, clubA, leg1, leg2);

        assertThatThrownBy(() -> TieResolver.winner(unplayedTie))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aLevelAggregateWithNoShootoutRecordedIsABugUpstream() {
        KnockoutTie level = tie(clubA, clubB, 0, 0, 1, 1); // 1-1 aggregate, no penalties set
        assertThatThrownBy(() -> TieResolver.winner(level))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void tiesOfOrdersByTieOrderNotByTheOpaqueTieId() {
        // Three ties whose tie_id sort order is deliberately the REVERSE of their tie_order —
        // if tiesOf ever sorted by tie_id this would come back scrambled while still looking
        // like a plausible bracket.
        Fixture tie0Leg1 = leg(clubA, clubB, 1, 0);
        Fixture tie1Leg1 = leg(clubA, clubB, 1, 1);
        Fixture tie2Leg1 = leg(clubA, clubB, 1, 2);
        // Force tie_id ordering opposite of tie_order ordering.
        forceTieIdOrderOpposite(tie0Leg1, tie1Leg1, tie2Leg1);

        List<KnockoutTie> ties = TieResolver.tiesOf(List.of(tie0Leg1, tie1Leg1, tie2Leg1));

        assertThat(ties).extracting(t -> t.firstLeg().getTieOrder()).containsExactly(0, 1, 2);
    }

    private Fixture leg(UUID home, UUID away, int legNumber, int tieOrder) {
        Fixture f = Fixture.create(SAVE_GAME_ID, COMPETITION_ID, legNumber, home, away, DATE);
        f.setLeg(legNumber);
        f.setTieOrder(tieOrder);
        return f;
    }

    /** Assigns tie_ids so their natural UUID sort order is the reverse of tie_order 0,1,2. */
    private void forceTieIdOrderOpposite(Fixture tie0, Fixture tie1, Fixture tie2) {
        UUID[] ids = { UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID() };
        java.util.Arrays.sort(ids);
        // Highest id -> tie_order 0, lowest id -> tie_order 2: exactly reversed.
        tie0.setTieId(ids[2]);
        tie1.setTieId(ids[1]);
        tie2.setTieId(ids[0]);
    }
}
