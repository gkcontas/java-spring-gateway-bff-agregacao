package com.gkcontas.gateway.web;

public record ApiError(int status, String error, String message) {
}
