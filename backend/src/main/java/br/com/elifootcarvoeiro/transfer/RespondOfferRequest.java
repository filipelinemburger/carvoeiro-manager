package br.com.elifootcarvoeiro.transfer;

import java.math.BigDecimal;

public record RespondOfferRequest(String action, BigDecimal counterAmount) {}
