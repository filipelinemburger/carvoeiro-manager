package br.com.carvoeiromanager.transfer;

import java.util.UUID;

public record TransferListingResponse(UUID playerId, boolean transferListed, String listedReason) {}
