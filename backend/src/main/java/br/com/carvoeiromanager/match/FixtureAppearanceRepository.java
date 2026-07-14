package br.com.carvoeiromanager.match;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface FixtureAppearanceRepository extends JpaRepository<FixtureAppearance, UUID> {
    List<FixtureAppearance> findByFixtureIdIn(Collection<UUID> fixtureIds);
    List<FixtureAppearance> findByPlayerId(UUID playerId);
    void deleteByFixtureIdIn(Collection<UUID> fixtureIds);
}
