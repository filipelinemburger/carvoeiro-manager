package br.com.carvoeiromanager.season;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

public interface SeasonEventRepository extends JpaRepository<SeasonEvent, UUID> {

    /**
     * The next unprocessed event in the whole calendar, <b>background competitions included</b>.
     *
     * <p>Almost never what production code wants: a background round dated between two managed
     * matchdays would be popped and reported to the player as their own fixture. Use
     * {@link #findNextManagedEvent} for "the next event the player is actually present for".
     * This one survives only for tests that assert the raw (event_date, sequence) ordering of the
     * calendar itself.
     */
    SeasonEvent findFirstBySaveGameIdAndProcessedFalseOrderByEventDateAscSequenceAsc(UUID saveGameId);

    List<SeasonEvent> findBySaveGameIdAndProcessedFalse(UUID saveGameId);

    List<SeasonEvent> findBySaveGameId(UUID saveGameId);

    /**
     * The next unprocessed event the managed club is present for: any TRANSFER stop, or a MATCH
     * round in a competition the club has a {@code competition_club} membership row in.
     *
     * <p>Membership — never a stored flag and never the background sequence constant — is what
     * makes a competition "managed", so this stays correct when a club moves between divisions
     * mid-career. Everything the calendar holds for the world the player does not play in is
     * skipped here and drained silently by
     * {@link BackgroundCompetitionService#resolveDue}.
     */
    @Query("""
            select e from SeasonEvent e
            where e.saveGameId = :saveGameId
              and e.processed = false
              and (e.type <> br.com.carvoeiromanager.season.SeasonEventType.MATCH
                   or exists (select 1 from CompetitionClub cc
                              where cc.saveGameId = e.saveGameId
                                and cc.competitionId = e.competitionId
                                and cc.clubId = :managedClubId))
            order by e.eventDate asc, e.sequence asc
            """)
    List<SeasonEvent> findNextManagedEvents(@Param("saveGameId") UUID saveGameId,
                                            @Param("managedClubId") UUID managedClubId,
                                            Limit limit);

    /** @see #findNextManagedEvents — null when the player has nothing left on the calendar. */
    default SeasonEvent findNextManagedEvent(UUID saveGameId, UUID managedClubId) {
        List<SeasonEvent> next = findNextManagedEvents(saveGameId, managedClubId, Limit.of(1));
        return next.isEmpty() ? null : next.getFirst();
    }

    /** {@link #findNextManagedEvents} narrowed to MATCH rounds — the player's next fixture. */
    @Query("""
            select e from SeasonEvent e
            where e.saveGameId = :saveGameId
              and e.processed = false
              and e.type = br.com.carvoeiromanager.season.SeasonEventType.MATCH
              and exists (select 1 from CompetitionClub cc
                          where cc.saveGameId = e.saveGameId
                            and cc.competitionId = e.competitionId
                            and cc.clubId = :managedClubId)
            order by e.eventDate asc, e.sequence asc
            """)
    List<SeasonEvent> findNextManagedMatchEvents(@Param("saveGameId") UUID saveGameId,
                                                 @Param("managedClubId") UUID managedClubId,
                                                 Limit limit);

    /** @see #findNextManagedMatchEvents — null when no managed MATCH round remains. */
    default SeasonEvent findNextManagedMatchEvent(UUID saveGameId, UUID managedClubId) {
        List<SeasonEvent> next = findNextManagedMatchEvents(saveGameId, managedClubId, Limit.of(1));
        return next.isEmpty() ? null : next.getFirst();
    }

    @Transactional
    void deleteBySaveGameId(UUID saveGameId);
}
