package br.com.carvoeiromanager.transfer;

/**
 * Everything {@link TransferNegotiator#respondToBuy} needs to price a buy offer from the
 * seller's point of view: whether the player is already transfer-listed, whether they're a key
 * player in their own squad, whether their position lacks cover, and the selling club's
 * financial health. Built by {@link SellerContextFactory}.
 */
public record SellerContext(boolean listed, boolean keyPlayer, boolean noCover,
                             ClubFinancialHealthEvaluator.FinancialHealth health) {
}
