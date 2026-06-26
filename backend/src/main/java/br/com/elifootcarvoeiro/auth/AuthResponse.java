package br.com.elifootcarvoeiro.auth;

import java.util.UUID;

public record AuthResponse(String token, String username, UUID userId) {}
