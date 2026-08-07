package br.com.carvoeiromanager.club;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ClubRepository extends JpaRepository<Club, UUID> {
    List<Club> findBySaveGameId(UUID saveGameId);

    Optional<Club> findBySaveGameIdAndShortName(UUID saveGameId, String shortName);
}
