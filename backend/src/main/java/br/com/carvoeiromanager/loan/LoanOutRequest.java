package br.com.carvoeiromanager.loan;

import java.util.UUID;

public record LoanOutRequest(UUID playerId, UUID borrowerClubId, int ownerWageShare,
                             boolean mayFaceOwner) {}
