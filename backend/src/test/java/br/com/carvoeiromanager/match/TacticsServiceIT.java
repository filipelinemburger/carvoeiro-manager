package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.club.Club;
import br.com.carvoeiromanager.club.ClubRepository;
import br.com.carvoeiromanager.player.GameStyle;
import br.com.carvoeiromanager.player.Mentality;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRepository;
import br.com.carvoeiromanager.player.DetailedPosition;
import br.com.carvoeiromanager.player.RoleArchetype;
import br.com.carvoeiromanager.player.RoleAssignment;
import br.com.carvoeiromanager.player.Position;
import br.com.carvoeiromanager.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class TacticsServiceIT {

    @Autowired TacticsService tacticsService;
    @Autowired SaveGameService saveGameService;
    @Autowired UserRepository userRepository;
    @Autowired PlayerRepository playerRepository;
    @Autowired LineupRepository lineupRepository;
    @Autowired LineupPlayerRepository lineupPlayerRepository;
    @Autowired ClubRepository clubRepository;

    private record Career(UUID userId, SaveGameResponse save) {}

    private Career createCareer() {
        User user = User.create("tacsvc_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        return new Career(user.getId(), saveGameService.createCareer(user.getId()));
    }

    /** Synthetic XI for the read-only default test (no persistence -> no FK needed). */
    private Player mk(Position pos, int finishing, int passing) {
        return Player.create(UUID.randomUUID(), UUID.randomUUID(), "T", pos, 25, "BRA",
                70, finishing, passing, 70, pos == Position.GK ? 70 : 10, 70, 72);
    }

    @Test
    void defaultProfileWhenNoLineupRow() {
        List<Player> xi = new ArrayList<>();
        xi.add(mk(Position.GK, 5, 50));
        for (int i = 0; i < 4; i++) xi.add(mk(Position.DEF, 30, 60));
        for (int i = 0; i < 4; i++) xi.add(mk(Position.MID, 50, 80));
        xi.add(mk(Position.ATT, 90, 40));
        xi.add(mk(Position.ATT, 70, 40));

        // No lineup row for these ids -> default profile (no DB write happens).
        TacticalProfile profile = tacticsService.profileFor(UUID.randomUUID(), UUID.randomUUID(), xi);

        assertThat(profile.mentality()).isEqualTo(Mentality.BALANCED);
        assertThat(profile.formation()).isEqualTo("4-4-2");
        UUID bestFinisher = xi.stream().max(java.util.Comparator.comparingInt(Player::getFinishing))
                .orElseThrow().getId();
        assertThat(profile.penaltyTakerId()).isEqualTo(bestFinisher);
        assertThat(profile.roles().values())
                .contains(RoleAssignment.defaultFor(DetailedPosition.GOLEIRO));
        assertThat(profile.roles().values()).allSatisfy(r ->
                assertThat(r.focus()).isEqualTo(r.archetype().canonicalDefaultFocus()));
    }

    @Test
    void loadsPersistedManagedProfile() {
        // Real career so the lineup/lineup_player FKs are satisfied.
        Career career = createCareer();
        UUID save = career.save().id();
        UUID club = career.save().managedClubId();
        List<Player> xi = playerRepository.findByClubId(club);
        UUID firstId = xi.get(0).getId();

        Lineup lineup = Lineup.create(save, club, "4-3-3");
        lineup.setMentality(Mentality.ATTACKING);
        lineupRepository.save(lineup);
        lineupPlayerRepository.save(
                LineupPlayer.create(lineup.getId(), firstId, true, RoleArchetype.GOALKEEPER));

        TacticalProfile profile = tacticsService.profileFor(save, club, xi);
        assertThat(profile.mentality()).isEqualTo(Mentality.ATTACKING);
        assertThat(profile.formation()).isEqualTo("4-3-3");
        assertThat(profile.roles().get(firstId)).isEqualTo(
                new RoleAssignment(RoleArchetype.GOALKEEPER, RoleArchetype.GOALKEEPER.canonicalDefaultFocus()));

        // position-default fill: a roster member with no persisted lineup_player row
        // still gets an assignment — its detailed-position default — not a missing entry.
        Player other = xi.get(1);
        assertThat(profile.roles()).containsKey(other.getId());
        assertThat(profile.roles().get(other.getId()))
                .isEqualTo(RoleAssignment.defaultFor(other.getDetailedPosition()));
    }

    @Test
    void managedLineupGameStyleFlowsIntoTheProfile() {
        Career career = createCareer();
        UUID save = career.save().id();
        UUID club = career.save().managedClubId();
        List<Player> xi = playerRepository.findByClubId(club);

        Lineup lineup = Lineup.create(save, club, "4-3-3");
        lineup.setGameStyle(GameStyle.CONTRA_ATAQUE);
        lineupRepository.save(lineup);

        TacticalProfile profile = tacticsService.profileFor(save, club, xi);
        assertThat(profile.gameStyle()).isEqualTo(GameStyle.CONTRA_ATAQUE);
    }

    @Test
    void aiClubDefaultGameStyleResolvesFromTheClubColumn() {
        Career career = createCareer();
        UUID save = career.save().id();
        UUID managedClub = career.save().managedClubId();

        Club ai = clubRepository.findBySaveGameId(save).stream()
                .filter(c -> !c.getId().equals(managedClub))
                .findFirst().orElseThrow();
        List<Player> xi = playerRepository.findByClubId(ai.getId());

        // A recognised style resolves through to the profile.
        ai.setDefaultGameStyle(GameStyle.PRESSAO_ALTA.name());
        clubRepository.save(ai);
        assertThat(tacticsService.profileFor(save, ai.getId(), xi).gameStyle())
                .isEqualTo(GameStyle.PRESSAO_ALTA);

        // A null column falls back to the neutral EQUILIBRADO (the Task-22 state for fresh careers,
        // since the seed does not yet author a style).
        ai.setDefaultGameStyle(null);
        clubRepository.save(ai);
        assertThat(tacticsService.profileFor(save, ai.getId(), xi).gameStyle())
                .isEqualTo(GameStyle.EQUILIBRADO);

        // An unparseable column also falls back to EQUILIBRADO rather than throwing.
        ai.setDefaultGameStyle("NOT_A_STYLE");
        clubRepository.save(ai);
        assertThat(tacticsService.profileFor(save, ai.getId(), xi).gameStyle())
                .isEqualTo(GameStyle.EQUILIBRADO);
    }

    @Test
    void aiClubsUseTheirOwnSeedTacticalIdentityNotAGlobalDefault() {
        Career career = createCareer();
        UUID save = career.save().id();
        UUID managedClub = career.save().managedClubId();

        var aiClubs = clubRepository.findBySaveGameId(save).stream()
                .filter(c -> !c.getId().equals(managedClub))
                .toList();

        // Find two AI clubs whose seed identities actually differ, so the
        // assertion is a real signal, not two clubs that happen to match.
        Club clubA = aiClubs.get(0);
        Club clubB = aiClubs.stream()
                .filter(c -> !c.getDefaultFormation().equals(clubA.getDefaultFormation())
                        || !c.getDefaultMentality().equals(clubA.getDefaultMentality())
                        || !c.getDefaultPressing().equals(clubA.getDefaultPressing())
                        || !c.getDefaultMarking().equals(clubA.getDefaultMarking()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected at least two AI clubs with distinct identities"));

        List<Player> xiA = playerRepository.findByClubId(clubA.getId());
        List<Player> xiB = playerRepository.findByClubId(clubB.getId());

        TacticalProfile profileA = tacticsService.profileFor(save, clubA.getId(), xiA);
        TacticalProfile profileB = tacticsService.profileFor(save, clubB.getId(), xiB);

        assertThat(profileA.formation()).isEqualTo(clubA.getDefaultFormation());
        assertThat(profileA.mentality().name()).isEqualTo(clubA.getDefaultMentality());
        assertThat(profileA.pressing().name()).isEqualTo(clubA.getDefaultPressing());
        assertThat(profileA.marking().name()).isEqualTo(clubA.getDefaultMarking());

        assertThat(profileB.formation()).isEqualTo(clubB.getDefaultFormation());
        assertThat(profileA).isNotEqualTo(profileB);
    }

    @Test
    void honorsPersistedSlotIndexesInTheTacticalProfile() {
        Career career = createCareer();
        UUID save = career.save().id();
        UUID club = career.save().managedClubId();
        List<Player> squad = playerRepository.findByClubId(club);
        Player gk = squad.stream().filter(p -> p.getPosition() == br.com.carvoeiromanager.player.Position.GK)
                .findFirst().orElseThrow();
        List<Player> outfield = squad.stream().filter(p -> p != gk).limit(10).toList();
        List<Player> xi = new ArrayList<>();
        xi.add(gk);
        xi.addAll(outfield);

        Lineup lineup = Lineup.create(save, club, "4-3-3");
        lineupRepository.save(lineup);
        // Persist GK -> slot 0, then the outfielders in REVERSE order into slots 1..10, so the
        // arrangement cannot coincide with what greedy would pick.
        LineupPlayer gkRow = LineupPlayer.create(lineup.getId(), gk.getId(), true, RoleArchetype.GOALKEEPER);
        gkRow.setSlotIndex(0);
        lineupPlayerRepository.save(gkRow);
        for (int i = 0; i < outfield.size(); i++) {
            Player p = outfield.get(outfield.size() - 1 - i);
            LineupPlayer row = LineupPlayer.create(lineup.getId(), p.getId(), true,
                    RoleArchetype.defaultFor(p.getDetailedPosition()));
            row.setSlotIndex(i + 1);
            lineupPlayerRepository.save(row);
        }

        TacticalProfile profile = tacticsService.profileFor(save, club, xi);

        List<FormationSlots.Slot> slots = FormationSlots.of("4-3-3");
        assertThat(profile.slotAssignments().get(gk.getId())).isEqualTo(slots.get(0));
        for (int i = 0; i < outfield.size(); i++) {
            Player p = outfield.get(outfield.size() - 1 - i);
            assertThat(profile.slotAssignments().get(p.getId()))
                    .as("player pinned to slot %d", i + 1)
                    .isEqualTo(slots.get(i + 1));
        }
    }

    @Test
    void substituteWithoutPersistedSlotInheritsTheVacatedSlot() {
        Career career = createCareer();
        UUID save = career.save().id();
        UUID club = career.save().managedClubId();
        List<Player> squad = playerRepository.findByClubId(club);
        Player gk = squad.stream().filter(p -> p.getPosition() == br.com.carvoeiromanager.player.Position.GK)
                .findFirst().orElseThrow();
        List<Player> outfield = squad.stream().filter(p -> p != gk).limit(11).toList();
        // Persist a full XI (gk + first 10 outfielders) with slots 0..10 …
        Lineup lineup = Lineup.create(save, club, "4-3-3");
        lineupRepository.save(lineup);
        LineupPlayer gkRow = LineupPlayer.create(lineup.getId(), gk.getId(), true, RoleArchetype.GOALKEEPER);
        gkRow.setSlotIndex(0);
        lineupPlayerRepository.save(gkRow);
        for (int i = 0; i < 10; i++) {
            LineupPlayer row = LineupPlayer.create(lineup.getId(), outfield.get(i).getId(), true,
                    RoleArchetype.defaultFor(outfield.get(i).getDetailedPosition()));
            row.setSlotIndex(i + 1);
            lineupPlayerRepository.save(row);
        }
        // …but the LIVE XI has the 11th outfielder on for the player who held slot 5.
        Player replaced = outfield.get(4); // slot 5
        Player sub = outfield.get(10);     // no lineup row at all
        List<Player> liveXi = new ArrayList<>();
        liveXi.add(gk);
        for (int i = 0; i < 10; i++) {
            liveXi.add(outfield.get(i) == replaced ? sub : outfield.get(i));
        }

        TacticalProfile profile = tacticsService.profileFor(save, club, liveXi);

        assertThat(profile.slotAssignments().get(sub.getId()))
                .isEqualTo(FormationSlots.of("4-3-3").get(5));
        assertThat(profile.slotAssignments()).doesNotContainKey(replaced.getId());
    }
}
