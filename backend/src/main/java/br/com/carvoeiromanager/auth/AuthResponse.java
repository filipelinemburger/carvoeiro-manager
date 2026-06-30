package br.com.carvoeiromanager.auth;

import java.util.UUID;

public record AuthResponse(String token, String username, UUID userId) {}
