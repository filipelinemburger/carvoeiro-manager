package br.com.carvoeiromanager.transfer;

import java.math.BigDecimal;

public record NegotiationResult(NegotiationDecision decision, BigDecimal counterAmount) {}
