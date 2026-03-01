package com.myfinaces.auth;

public record AuthSession(
    String uid,
    String email,
    String idToken,
    String refreshToken,
    long expiresAtEpochSec
) {
}
