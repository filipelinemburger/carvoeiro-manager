package br.com.carvoeiromanager.contract;

import java.math.BigDecimal;

/** status: AGREED (agreement set) or COUNTERED (demandedWage set, no agreement row). */
public record PreContractOfferResponse(String status, BigDecimal demandedWage,
                                       PreContractAgreementResponse agreement) {}
