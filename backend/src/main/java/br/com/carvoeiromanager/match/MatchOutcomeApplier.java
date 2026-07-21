package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.player.InjurySeverity;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.Position;
import br.com.carvoeiromanager.staff.StaffEffectsService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Pure player-state mutation for a simulated round: applies cards -> suspensions (a red expunges
 * that match's yellows from the accumulation tally) and form to players who played, and form
 * regression + suspension service to players who did not. No repository access; the caller
 * persists the mutated Player entities. Surfaces bookings/new-suspensions for the managed club.
 */
@Service
public class MatchOutcomeApplier {

    private final FitnessCalculator fitnessCalculator;
    private final StaffEffectsService staffEffectsService;

    public MatchOutcomeApplier(FitnessCalculator fitnessCalculator, StaffEffectsService staffEffectsService) {
        this.fitnessCalculator = fitnessCalculator;
        this.staffEffectsService = staffEffectsService;
    }

    public record TeamOutcome(List<Player> xi, int scored, int conceded, boolean managed) {
    }

    public static final class Summary {
        private final List<BookingResponse> bookings = new ArrayList<>();
        private final List<SuspensionResponse> newSuspensions = new ArrayList<>();
        private final List<InjuryResponse> newInjuries = new ArrayList<>();

        public List<BookingResponse> bookings() { return bookings; }
        public List<SuspensionResponse> newSuspensions() { return newSuspensions; }
        public List<InjuryResponse> newInjuries() { return newInjuries; }
    }

    public void applyFixture(List<AttributedEvent> events, TeamOutcome home, TeamOutcome away, Summary acc) {
        Map<UUID, Integer> goals = new HashMap<>();
        Map<UUID, Integer> yellows = new HashMap<>();
        Set<UUID> reds = new HashSet<>();
        for (AttributedEvent e : events) {
            if (e.type().isGoal()) {
                goals.merge(e.playerId(), 1, Integer::sum);
                continue;
            }
            switch (e.type()) {
                case YELLOW_CARD -> yellows.merge(e.playerId(), 1, Integer::sum);
                case RED_CARD -> reds.add(e.playerId());
                default -> { }
            }
        }
        applyTeam(home, goals, yellows, reds, acc);
        applyTeam(away, goals, yellows, reds, acc);
    }

    private void applyTeam(TeamOutcome team, Map<UUID, Integer> goals, Map<UUID, Integer> yellows,
                           Set<UUID> reds, Summary acc) {
        boolean won = team.scored() > team.conceded();
        boolean lost = team.scored() < team.conceded();
        for (Player p : team.xi()) {
            boolean sentOffThisMatch = reds.contains(p.getId());
            int yellowCount = yellows.getOrDefault(p.getId(), 0);
            for (int i = 0; i < yellowCount; i++) {
                if (team.managed()) {
                    acc.bookings().add(new BookingResponse(p.getId(), p.getName(), "YELLOW_CARD"));
                }
                // CBF rule: a red card (straight or second-yellow) expunges this match's yellows
                // from the accumulation tally — the send-off ban is the only punishment.
                if (sentOffThisMatch) {
                    continue;
                }
                p.addYellowCard();
                if (p.getYellowCards() % 3 == 0) {
                    p.addSuspension(1);
                    if (team.managed()) {
                        acc.newSuspensions().add(new SuspensionResponse(p.getId(), p.getName(), 1));
                    }
                }
            }
            if (reds.contains(p.getId())) {
                p.addSuspension(1);
                if (team.managed()) {
                    acc.bookings().add(new BookingResponse(p.getId(), p.getName(), "RED_CARD"));
                    acc.newSuspensions().add(new SuspensionResponse(p.getId(), p.getName(), 1));
                }
            }

            int delta = Math.min(2, goals.getOrDefault(p.getId(), 0));
            delta += won ? 1 : (lost ? -1 : 0);
            if (p.getPosition() == Position.GK || p.getPosition() == Position.DEF) {
                if (team.conceded() == 0) {
                    delta += 1;
                } else if (team.conceded() >= 3) {
                    delta -= 1;
                }
            }
            p.applyForm(delta);

            // Morale: match result, plus a lift for scoring and a hit for a red card.
            int moraleDelta = won ? 1 : (lost ? -1 : 0);
            if (goals.getOrDefault(p.getId(), 0) > 0) {
                moraleDelta += 1;
            }
            if (reds.contains(p.getId())) {
                moraleDelta -= 1;
            }
            p.applyMorale(moraleDelta);
        }
    }

    public void applyNonPlayers(List<Player> nonPlayers) {
        for (Player p : nonPlayers) {
            if (p.getForm() > 0) {
                p.applyForm(-1);
            } else if (p.getForm() < 0) {
                p.applyForm(1);
            }
            if (p.getSuspensionRemaining() > 0) {
                p.decrementSuspension();
            }
            if (p.getInjuryMatches() > 0) {
                p.tickInjury();
            }
            // Morale: players who did not feature drift toward mild discontent (-1).
            if (p.getMorale() > -1) {
                p.applyMorale(-1);
            } else if (p.getMorale() < -1) {
                p.applyMorale(1);
            }
        }
    }

    /**
     * Applies injuries already decided by the live timeline (no roll). Managed-side injuries are
     * surfaced in the summary. Mirrors {@link #applyInjuries} but consumes pre-drawn {@link TimedInjury}s.
     */
    public void applyInjuriesFromTimeline(List<TimedInjury> injuries, Map<UUID, Player> byId,
                                          boolean managedIsHome, Summary acc) {
        for (TimedInjury inj : injuries) {
            Player p = byId.get(inj.playerId());
            if (p == null) {
                continue;
            }
            p.applyInjury(inj.severity(), inj.layoffMatches());
            if (inj.home() == managedIsHome) {
                acc.newInjuries().add(new InjuryResponse(p.getId(), p.getName(),
                        inj.layoffMatches(), inj.severity().name()));
            }
        }
    }

    /**
     * Rolls injuries for players who played, using a separate {@link Random} seeded off the match
     * seed so match/event RNG is unaffected. Chance rises as effective fitness falls and with age;
     * on a hit a severity tier and its layoff are drawn. Newly injured managed players are recorded.
     */
    public void applyInjuries(List<Player> playedXi, Map<UUID, Integer> effectiveFitnessById,
                              Random rng, boolean managed, Summary acc) {
        for (Player p : playedXi) {
            int fitness = effectiveFitnessById.getOrDefault(p.getId(), 100);
            double chance = fitnessCalculator.injuryChance(fitness, p.getAge())
                    * staffEffectsService.injuryRiskMultiplierFor(p.getClubId(), managed);
            if (rng.nextDouble() < chance) {
                InjurySeverity severity = InjurySeverity.roll(rng);
                int matches = severity.layoffMatches(rng);
                p.applyInjury(severity, matches);
                if (managed) {
                    acc.newInjuries().add(new InjuryResponse(p.getId(), p.getName(), matches, severity.name()));
                }
            }
        }
    }
}
