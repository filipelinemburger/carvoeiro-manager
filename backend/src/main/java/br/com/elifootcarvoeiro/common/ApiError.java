package br.com.elifootcarvoeiro.common;

public record ApiError(String error, String message, int status) {}
