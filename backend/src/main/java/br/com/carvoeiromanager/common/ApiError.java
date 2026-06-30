package br.com.carvoeiromanager.common;

public record ApiError(String error, String message, int status) {}
