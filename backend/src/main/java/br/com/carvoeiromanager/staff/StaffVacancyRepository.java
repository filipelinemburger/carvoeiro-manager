package br.com.carvoeiromanager.staff;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface StaffVacancyRepository extends JpaRepository<StaffVacancy, UUID> {
    boolean existsByClubIdAndRole(UUID clubId, StaffRole role);
}
