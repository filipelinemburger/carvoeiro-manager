package br.com.carvoeiromanager.staff;

import java.math.BigDecimal;

public record StaffResponse(String name, String quality, BigDecimal wage,
                            int primaryAttribute, int secondaryAttribute) {
}
