package br.com.carvoeiromanager.staff;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StaffRepository extends JpaRepository<Staff, UUID> {
    List<Staff> findByClubId(UUID clubId);
    Optional<Staff> findByClubIdAndRole(UUID clubId, StaffRole role);
    List<Staff> findBySaveGameId(UUID saveGameId);
    void deleteByClubIdAndRole(UUID clubId, StaffRole role);
}
