package br.com.elifootcarvoeiro.match;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface LineupPlayerRepository extends JpaRepository<LineupPlayer, LineupPlayerId> {
    List<LineupPlayer> findByLineupId(UUID lineupId);
}
