package br.com.carvoeiromanager.match;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface MatchEventRepository extends JpaRepository<MatchEvent, UUID> {
    List<MatchEvent> findByFixtureIdInAndTypeIn(Collection<UUID> fixtureIds, Collection<String> types);
    void deleteByFixtureIdIn(Collection<UUID> fixtureIds);
}
