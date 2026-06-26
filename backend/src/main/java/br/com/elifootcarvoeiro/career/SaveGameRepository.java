package br.com.elifootcarvoeiro.career;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface SaveGameRepository extends JpaRepository<SaveGame, UUID> {
}
