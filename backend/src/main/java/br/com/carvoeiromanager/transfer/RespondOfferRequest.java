package br.com.carvoeiromanager.transfer;

import java.math.BigDecimal;

public record RespondOfferRequest(String action, BigDecimal counterAmount) {}
