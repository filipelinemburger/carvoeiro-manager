package br.com.carvoeiromanager.board;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface BoardStateRepository extends JpaRepository<BoardState, UUID> {
}
