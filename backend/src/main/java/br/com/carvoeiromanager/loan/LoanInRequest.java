package br.com.carvoeiromanager.loan;

import java.util.UUID;

public record LoanInRequest(UUID playerId, int ownerWageShare, boolean mayFaceOwner) {}
