package com.myfinaces.auth;

import com.myfinaces.db.SessionRepository;

import java.io.IOException;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.Callable;

/**
 * Mantiene un {@link AuthSession} con idToken vigente para la sincronización.
 *
 * <p>El idToken de Firebase expira a la hora del login; esta pieza lo renueva
 * de forma preventiva ({@link #validSession()}) y de forma reactiva cuando una
 * llamada autenticada devuelve HTTP 401 ({@link #executeWithAuthRetry(Callable)}),
 * persistiendo la sesión renovada mediante {@link SessionRepository}.
 */
public final class AuthSessionManager {

    private static final long REFRESH_MARGIN_SEC = 60L;

    @FunctionalInterface
    public interface TokenRefresher {
        AuthSession refresh(AuthSession session) throws IOException, InterruptedException;
    }

    /**
     * La autenticación no pudo recuperarse: refresh inválido/revocado o el
     * reintento siguió devolviendo 401. La sincronización debe abortarse.
     */
    public static final class SyncAuthenticationException extends RuntimeException {
        public SyncAuthenticationException(String message) {
            super(message);
        }

        public SyncAuthenticationException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final SessionRepository sessionRepository;
    private final TokenRefresher tokenRefresher;
    private volatile AuthSession current;

    public AuthSessionManager(
        SessionRepository sessionRepository,
        TokenRefresher tokenRefresher,
        AuthSession initial
    ) {
        this.sessionRepository = Objects.requireNonNull(sessionRepository, "sessionRepository");
        this.tokenRefresher = Objects.requireNonNull(tokenRefresher, "tokenRefresher");
        this.current = Objects.requireNonNull(initial, "initial");
    }

    public AuthSession current() {
        return current;
    }

    /**
     * Devuelve una sesión cuyo idToken está vigente. Si el token actual expiró
     * o está dentro del margen de seguridad, lo renueva antes de devolverla.
     */
    public synchronized AuthSession validSession() {
        long now = Instant.now().getEpochSecond();
        if (current.expiresAtEpochSec() - REFRESH_MARGIN_SEC > now) {
            return current;
        }
        return refreshNow();
    }

    /**
     * Renueva el idToken con el refreshToken, actualiza la sesión en memoria y
     * la persiste. Cualquier fallo aborta con {@link SyncAuthenticationException}.
     */
    public synchronized AuthSession refreshNow() {
        final AuthSession refreshed;
        try {
            refreshed = tokenRefresher.refresh(current);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SyncAuthenticationException("Token refresh interrupted", e);
        } catch (Exception e) {
            throw new SyncAuthenticationException("Token refresh failed: " + e.getMessage(), e);
        }
        if (refreshed == null || refreshed.idToken() == null || refreshed.idToken().isBlank()) {
            throw new SyncAuthenticationException("Token refresh returned an empty idToken");
        }
        current = refreshed;
        try {
            sessionRepository.save(refreshed);
        } catch (Exception e) {
            System.out.println("[AuthSessionManager] could not persist refreshed session: " + e.getMessage());
        }
        return refreshed;
    }

    /**
     * Ejecuta una operación autenticada. Si devuelve HTTP 401, renueva el token
     * y la reintenta exactamente una vez; si el reintento sigue devolviendo 401
     * o el refresh falla, aborta con {@link SyncAuthenticationException}.
     */
    public <T> T executeWithAuthRetry(Callable<T> operation) throws Exception {
        try {
            return operation.call();
        } catch (Exception first) {
            if (!isUnauthorized(first)) {
                throw first;
            }
            refreshNow();
            try {
                return operation.call();
            } catch (Exception second) {
                if (isUnauthorized(second)) {
                    throw new SyncAuthenticationException(
                        "Request still unauthorized after token refresh", second);
                }
                throw second;
            }
        }
    }

    /**
     * Detecta respuestas HTTP 401 propagadas por {@code FirestoreSyncService},
     * cuyos errores llevan el patrón {@code "...(401)..."} en el mensaje.
     */
    public static boolean isUnauthorized(Throwable t) {
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            if (cur instanceof SyncAuthenticationException) {
                return false;
            }
            String message = cur.getMessage();
            if (message != null && message.contains("(401")) {
                return true;
            }
        }
        return false;
    }
}
