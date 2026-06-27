package br.com.elifootcarvoeiro.transfer;

import java.math.BigDecimal;

public record NegotiationResult(NegotiationDecision decision, BigDecimal counterAmount) {}
