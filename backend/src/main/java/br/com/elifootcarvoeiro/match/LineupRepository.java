package br.com.elifootcarvoeiro.match;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface LineupRepository extends JpaRepository<Lineup, UUID> {
}
