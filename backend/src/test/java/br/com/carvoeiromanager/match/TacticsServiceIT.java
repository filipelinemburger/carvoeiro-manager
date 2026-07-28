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
import br.com.carvoeiromanager.player.RoleFocus;
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
        // The GK never has alt positions or a promotable familiar role, so it's always the default.
        Player gk = xi.get(0);
        assertThat(profile.roles().get(gk.getId()))
                .isEqualTo(RoleAssignment.defaultFor(DetailedPosition.GOLEIRO));
        // Every other starter's assignment is valid for whatever slot it actually landed in — a
        // familiar-aware pick can legitimately deviate from the position's blunt default (Task 5),
        // so this checks the real invariant (archetype validity + matching focus) rather than
        // hard-coding a specific archetype that depends on this XI's random detailed positions.
        for (Player p : xi) {
            RoleAssignment assignment = profile.roles().get(p.getId());
            FormationSlots.Slot slot = profile.slotAssignments().get(p.getId());
            DetailedPosition pos = slot != null ? slot.accepted().iterator().next() : p.getDetailedPosition();
            assertThat(assignment.archetype().validFor(pos)).as("archetype valid for %s", pos).isTrue();
            assertThat(assignment.focus()).isEqualTo(assignment.archetype().defaultFocusFor(pos));
        }
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

        // familiar-aware fill: a roster member with no persisted lineup_player row still gets a
        // valid assignment for whatever slot it actually landed in — not a missing entry, and not
        // necessarily its blunt position default now that the fill is familiar-aware (Task 5).
        Player other = xi.get(1);
        assertThat(profile.roles()).containsKey(other.getId());
        RoleAssignment otherAssignment = profile.roles().get(other.getId());
        FormationSlots.Slot otherSlot = profile.slotAssignments().get(other.getId());
        DetailedPosition otherPos = otherSlot != null
                ? otherSlot.accepted().iterator().next() : other.getDetailedPosition();
        assertThat(otherAssignment.archetype().validFor(otherPos)).isTrue();
        assertThat(otherAssignment.focus()).isEqualTo(otherAssignment.archetype().defaultFocusFor(otherPos));
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

    @Test
    void aiProfilePicksTheBestFamiliarArchetypeValidForTheAssignedSlot() {
        // A well-formed, hand-crafted 4-4-2 XI (no randomly-drawn detailed positions) so the right
        // back is unambiguously the sole natural candidate for the LD slot.
        Player gk = detailed(Position.GK, DetailedPosition.GOLEIRO);
        Player rightBack = detailed(Position.DEF, DetailedPosition.LATERAL_DIREITO);
        rightBack.setFamiliarRoles(List.of("WINGBACK++"));
        Player zagA = detailed(Position.DEF, DetailedPosition.ZAGUEIRO);
        Player zagB = detailed(Position.DEF, DetailedPosition.ZAGUEIRO);
        Player leftBack = detailed(Position.DEF, DetailedPosition.LATERAL_ESQUERDO);
        Player md = detailed(Position.MID, DetailedPosition.MEIA_DIREITA);
        Player vol = detailed(Position.MID, DetailedPosition.VOLANTE);
        Player mc = detailed(Position.MID, DetailedPosition.MEIA_CENTRAL);
        Player me = detailed(Position.MID, DetailedPosition.MEIA_ESQUERDA);
        Player stA = detailed(Position.ATT, DetailedPosition.ATACANTE);
        Player stB = detailed(Position.ATT, DetailedPosition.ATACANTE);
        List<Player> xi = List.of(gk, rightBack, zagA, zagB, leftBack, md, vol, mc, me, stA, stB);

        // No lineup row anywhere -> default (AI) profile, no DB write happens.
        TacticalProfile profile = tacticsService.defaultProfile(UUID.randomUUID(), xi);

        FormationSlots.Slot ldSlot = FormationSlots.of("4-4-2").get(1);
        assertThat(profile.slotAssignments().get(rightBack.getId())).isEqualTo(ldSlot);
        // WINGBACK (familiar ++), not FULLBACK (the blunt LATERAL_DIREITO default) — and its own
        // default focus for LD, BALANCED, not WINGBACK's canonical default (also BALANCED here, but
        // resolved via defaultFocusFor(LD) rather than hard-coded).
        assertThat(profile.roles().get(rightBack.getId())).isEqualTo(
                new RoleAssignment(RoleArchetype.WINGBACK, RoleArchetype.WINGBACK.defaultFocusFor(DetailedPosition.LATERAL_DIREITO)));
        assertThat(profile.roles().get(rightBack.getId()).focus()).isEqualTo(RoleFocus.BALANCED);
    }

    @Test
    void managedProfileHonoursExplicitSlots() {
        Career career = createCareer();
        UUID save = career.save().id();
        UUID club = career.save().managedClubId();
        List<Player> squad = playerRepository.findByClubId(club);
        Player gk = squad.stream().filter(p -> p.getPosition() == Position.GK).findFirst().orElseThrow();
        List<Player> outfield = squad.stream().filter(p -> p != gk).limit(10).toList();
        List<Player> xi = new ArrayList<>();
        xi.add(gk);
        xi.addAll(outfield);

        Lineup lineup = Lineup.create(save, club, "4-4-2");
        lineupRepository.save(lineup);
        LineupPlayer gkRow = LineupPlayer.create(lineup.getId(), gk.getId(), true, RoleArchetype.GOALKEEPER);
        gkRow.setSlotIndex(0);
        lineupPlayerRepository.save(gkRow);
        // Force the first outfielder into a striker slot regardless of his natural position.
        Player forced = outfield.get(0);
        LineupPlayer forcedRow = LineupPlayer.create(lineup.getId(), forced.getId(), true,
                RoleArchetype.defaultFor(forced.getDetailedPosition()));
        forcedRow.setSlotIndex(9);
        lineupPlayerRepository.save(forcedRow);

        TacticalProfile profile = tacticsService.profileFor(save, club, xi);

        List<FormationSlots.Slot> slots = FormationSlots.of("4-4-2");
        assertThat(profile.slotAssignments().get(gk.getId())).isEqualTo(slots.get(0));
        assertThat(profile.slotAssignments().get(forced.getId())).isEqualTo(slots.get(9));
    }

    private static Player detailed(Position pos, DetailedPosition detailedPosition) {
        Player p = Player.create(UUID.randomUUID(), UUID.randomUUID(), "T", pos, 25, "BRA",
                70, 70, 70, 70, pos == Position.GK ? 70 : 10, 70, 70);
        p.setPositions(detailedPosition, List.of());
        return p;
    }

    /**
     * Reproduces the Task-5 review finding: a club with no goalkeeper at all (the scenario
     * {@code LineupService#improvisedKeeper} now handles instead of throwing NO_GK_FOUND) hands
     * {@code SlotAssigner.assignToSlots} an XI in which NO player's {@link Player#positions()}
     * contains GOLEIRO — the GK slot has zero natural candidates. Before the fix, the scarcest-first
     * sort treated "zero candidates" as the MOST scarce slot and processed it first; its distance
     * fallback (every outfielder is equidistant — {@code DetailedPosition#distance} to GOLEIRO is a
     * constant 3) then degenerated into "pick the highest-overall player in the whole XI", which can
     * steal a genuine natural (here, the sole MEIA_DIREITA) out of its own slot before that slot's
     * turn — cascading into a garbled assignment. The unfillable GK slot should never be able to
     * outrank a slot it cannot protect anything from.
     */
    @Test
    void keeperlessXiDoesNotLetTheUnfillableGkSlotStealANaturalFromItsSlot() {
        Player ld = player(Position.DEF, DetailedPosition.LATERAL_DIREITO, 70);
        Player zagA = player(Position.DEF, DetailedPosition.ZAGUEIRO, 70);
        Player zagB = player(Position.DEF, DetailedPosition.ZAGUEIRO, 70);
        // A third ZAGUEIRO: an over-represented specialist with no ZAG slot left once zagA/zagB
        // naturally fill both — the genuinely "spare" player who should end up improvising in the
        // unfillable GK slot, NOT the natural MD below.
        Player zagC = player(Position.DEF, DetailedPosition.ZAGUEIRO, 60);
        Player le = player(Position.DEF, DetailedPosition.LATERAL_ESQUERDO, 70);
        // Rated highest in the whole XI so the pre-fix bug (GK slot processed first; its distance
        // fallback ties every outfielder at 3, so it degenerates to "highest overall wins") grabs
        // THIS player — the sole natural for the MD slot — instead of leaving him there.
        Player md = player(Position.MID, DetailedPosition.MEIA_DIREITA, 95);
        Player vol = player(Position.MID, DetailedPosition.VOLANTE, 70);
        Player mc = player(Position.MID, DetailedPosition.MEIA_CENTRAL, 70);
        Player me = player(Position.MID, DetailedPosition.MEIA_ESQUERDA, 70);
        Player stA = player(Position.ATT, DetailedPosition.ATACANTE, 70);
        Player stB = player(Position.ATT, DetailedPosition.ATACANTE, 70);
        List<Player> xi = List.of(ld, zagA, zagB, zagC, le, md, vol, mc, me, stA, stB);
        assertThat(xi).hasSize(11);
        assertThat(xi).noneMatch(p -> p.positions().contains(DetailedPosition.GOLEIRO));

        // AI/default profile path — same SlotAssigner.assignToSlots(xi, formation) full-11 call
        // bestXi-originated managed-club XIs go through too (TacticsService#fromLineup's un-pinned
        // branch).
        TacticalProfile profile = tacticsService.defaultProfile(UUID.randomUUID(), xi);

        List<FormationSlots.Slot> slots = FormationSlots.of("4-4-2");
        // The genuine MD natural must stay in the MD slot...
        assertThat(profile.slotAssignments().get(md.getId())).isEqualTo(slots.get(5));
        assertThat(profile.roles().get(md.getId()).archetype().validFor(DetailedPosition.MEIA_DIREITA))
                .as("MD natural must NOT be graded with a goalkeeper archetype").isTrue();
        // ...and so must every other genuine natural.
        assertThat(profile.slotAssignments().get(ld.getId())).isEqualTo(slots.get(1));
        assertThat(profile.slotAssignments().get(le.getId())).isEqualTo(slots.get(4));
        assertThat(profile.slotAssignments().get(vol.getId())).isEqualTo(slots.get(6));
        assertThat(profile.slotAssignments().get(mc.getId())).isEqualTo(slots.get(7));
        assertThat(profile.slotAssignments().get(me.getId())).isEqualTo(slots.get(8));
        // ZAG (idx 2/3) and ST (idx 9/10) are each backed by the SAME shared FormationSlots.Slot
        // instance at both indexes (FormationSlots reuses the ZAG/ST constants), so the two indexes
        // are equal by value — checking accepted() membership (rather than Set.of, which would
        // reject the "duplicate" equal elements) is the correct way to prove both landed on a
        // natural ZAG/ST slot instead of one of them being displaced.
        assertThat(profile.slotAssignments().get(zagA.getId()).accepted())
                .containsExactly(DetailedPosition.ZAGUEIRO);
        assertThat(profile.slotAssignments().get(zagB.getId()).accepted())
                .containsExactly(DetailedPosition.ZAGUEIRO);
        assertThat(profile.slotAssignments().get(stA.getId()).accepted())
                .containsExactly(DetailedPosition.ATACANTE);
        assertThat(profile.slotAssignments().get(stB.getId()).accepted())
                .containsExactly(DetailedPosition.ATACANTE);
        // The spare (3rd) zagueiro — the only player left over once every natural slot is filled —
        // is the one who ends up improvising in the unfillable GK slot, not a stolen natural.
        assertThat(profile.slotAssignments().get(zagC.getId())).isEqualTo(slots.get(0));
    }

    private static Player player(Position pos, DetailedPosition detailedPosition, int overall) {
        Player p = Player.create(UUID.randomUUID(), UUID.randomUUID(), "T", pos, 25, "BRA",
                70, 70, 70, 70, pos == Position.GK ? 70 : 10, 70, overall);
        p.setPositions(detailedPosition, List.of());
        return p;
    }
}
