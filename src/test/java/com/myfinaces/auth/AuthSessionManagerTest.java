package com.myfinaces.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.myfinaces.auth.AuthSessionManager.SyncAuthenticationException;
import com.myfinaces.db.SessionRepository;
import com.myfinaces.db.SqliteDatabase;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AuthSessionManagerTest {

    private static final String UID = "uid-1";

    @TempDir
    Path temporaryDirectory;

    private SessionRepository sessionRepository;

    @BeforeEach
    void setUp() throws Exception {
        SqliteDatabase database = new SqliteDatabase(temporaryDirectory.resolve("session.db"));
        sessionRepository = new SessionRepository(database);
        sessionRepository.init();
    }

    private static AuthSession session(String idToken, String refreshToken, long expiresAt) {
        return new AuthSession(UID, "user@test.com", "User", idToken, refreshToken, expiresAt);
    }

    private static RuntimeException unauthorized() {
        return new RuntimeException("Firestore pull failed (401): UNAUTHENTICATED");
    }

    @Test
    void validTokenDoesNotRefresh() {
        long now = Instant.now().getEpochSecond();
        AuthSession initial = session("token-1", "refresh-1", now + 3600);
        AtomicInteger refreshCount = new AtomicInteger();
        AuthSessionManager manager = new AuthSessionManager(
            sessionRepository,
            s -> {
                refreshCount.incrementAndGet();
                return session("token-2", "refresh-2", now + 7200);
            },
            initial
        );

        AuthSession result = manager.validSession();

        assertSame(initial, result);
        assertEquals("token-1", manager.current().idToken());
        assertEquals(0, refreshCount.get());
    }

    @Test
    void expiredTokenRefreshesExactlyOnce() {
        long now = Instant.now().getEpochSecond();
        AuthSession initial = session("token-1", "refresh-1", now - 10);
        AtomicInteger refreshCount = new AtomicInteger();
        AuthSessionManager manager = new AuthSessionManager(
            sessionRepository,
            s -> {
                refreshCount.incrementAndGet();
                return session("token-2", "refresh-2", now + 3600);
            },
            initial
        );

        AuthSession first = manager.validSession();
        AuthSession second = manager.validSession();

        assertEquals("token-2", first.idToken());
        assertSame(first, second);
        assertEquals(1, refreshCount.get());
    }

    @Test
    void successfulRefreshPersistsNewSession() throws Exception {
        long now = Instant.now().getEpochSecond();
        AuthSession initial = session("token-1", "refresh-1", now - 10);
        AuthSessionManager manager = new AuthSessionManager(
            sessionRepository,
            s -> session("token-2", "refresh-2", now + 3600),
            initial
        );

        manager.validSession();

        AuthSession persisted = sessionRepository.load().orElseThrow();
        assertEquals("token-2", persisted.idToken());
        assertEquals("refresh-2", persisted.refreshToken());
        assertEquals(now + 3600, persisted.expiresAtEpochSec());
        assertEquals(UID, persisted.uid());
    }

    @Test
    void unauthorizedOperationRefreshesRetriesAndCompletes() throws Exception {
        long now = Instant.now().getEpochSecond();
        AuthSession initial = session("token-1", "refresh-1", now + 3600);
        AtomicInteger refreshCount = new AtomicInteger();
        AuthSessionManager manager = new AuthSessionManager(
            sessionRepository,
            s -> {
                refreshCount.incrementAndGet();
                return session("token-2", "refresh-2", now + 7200);
            },
            initial
        );

        AtomicInteger calls = new AtomicInteger();
        Callable<String> operation = () -> {
            if (calls.incrementAndGet() == 1) {
                throw unauthorized();
            }
            return "ok:" + manager.current().idToken();
        };

        String result = manager.executeWithAuthRetry(operation);

        assertEquals("ok:token-2", result);
        assertEquals(2, calls.get());
        assertEquals(1, refreshCount.get());
        assertEquals("token-2", manager.current().idToken());
        AuthSession persisted = sessionRepository.load().orElseThrow();
        assertEquals("token-2", persisted.idToken());
    }

    @Test
    void failedRefreshAbortsOperationWithoutRetry() {
        long now = Instant.now().getEpochSecond();
        AuthSession initial = session("token-1", "refresh-1", now - 10);
        AtomicInteger refreshCount = new AtomicInteger();
        AuthSessionManager manager = new AuthSessionManager(
            sessionRepository,
            s -> {
                refreshCount.incrementAndGet();
                throw new IOException("invalid_grant");
            },
            initial
        );

        assertThrows(SyncAuthenticationException.class, manager::validSession);

        AtomicInteger calls = new AtomicInteger();
        Callable<String> operation = () -> {
            calls.incrementAndGet();
            throw unauthorized();
        };
        assertThrows(SyncAuthenticationException.class, () -> manager.executeWithAuthRetry(operation));

        assertEquals(2, refreshCount.get());
        assertEquals(1, calls.get());
        assertEquals("token-1", manager.current().idToken());
    }

    @Test
    void renewedSessionIsSharedAcrossCollectionsWithSingleRefresh() throws Exception {
        long now = Instant.now().getEpochSecond();
        AuthSession initial = session("token-1", "refresh-1", now - 10);
        AtomicInteger refreshCount = new AtomicInteger();
        AuthSessionManager manager = new AuthSessionManager(
            sessionRepository,
            s -> {
                refreshCount.incrementAndGet();
                return session("token-2", "refresh-2", now + 3600);
            },
            initial
        );

        manager.validSession();

        StringBuilder seen = new StringBuilder();
        for (int i = 0; i < 3; i++) {
            String token = manager.executeWithAuthRetry(() -> manager.current().idToken());
            seen.append(token).append(';');
        }

        assertEquals("token-2;token-2;token-2;", seen.toString());
        assertEquals(1, refreshCount.get());
    }

    @Test
    void secondUnauthorizedAfterRetryPropagatesAsAuthFailure() {
        long now = Instant.now().getEpochSecond();
        AuthSession initial = session("token-1", "refresh-1", now + 3600);
        AtomicInteger refreshCount = new AtomicInteger();
        AuthSessionManager manager = new AuthSessionManager(
            sessionRepository,
            s -> {
                refreshCount.incrementAndGet();
                return session("token-2", "refresh-2", now + 7200);
            },
            initial
        );

        AtomicInteger calls = new AtomicInteger();
        Callable<String> operation = () -> {
            calls.incrementAndGet();
            throw unauthorized();
        };

        assertThrows(SyncAuthenticationException.class, () -> manager.executeWithAuthRetry(operation));
        assertEquals(2, calls.get());
        assertEquals(1, refreshCount.get());
    }

    @Test
    void nonUnauthorizedErrorsPropagateWithoutRefresh() {
        long now = Instant.now().getEpochSecond();
        AuthSession initial = session("token-1", "refresh-1", now + 3600);
        AtomicInteger refreshCount = new AtomicInteger();
        AuthSessionManager manager = new AuthSessionManager(
            sessionRepository,
            s -> {
                refreshCount.incrementAndGet();
                return session("token-2", "refresh-2", now + 7200);
            },
            initial
        );

        Callable<String> operation = () -> {
            throw new RuntimeException("Firestore pull failed (500): backend error");
        };

        RuntimeException thrown = assertThrows(
            RuntimeException.class,
            () -> manager.executeWithAuthRetry(operation)
        );
        assertEquals("Firestore pull failed (500): backend error", thrown.getMessage());
        assertEquals(0, refreshCount.get());
    }
}
