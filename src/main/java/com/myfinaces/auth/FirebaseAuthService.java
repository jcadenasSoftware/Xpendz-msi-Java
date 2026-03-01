package com.myfinaces.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

public final class FirebaseAuthService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient http;
    private final String apiKey;

    public FirebaseAuthService(String apiKey) {
        this.http = HttpClient.newHttpClient();
        this.apiKey = apiKey;
    }

    public AuthSession signUpWithEmailPassword(String email, String password) throws IOException, InterruptedException {
        return signWithEmailPassword("accounts:signUp", email, password);
    }

    public AuthSession signInWithEmailPassword(String email, String password) throws IOException, InterruptedException {
        return signWithEmailPassword("accounts:signInWithPassword", email, password);
    }

    public AuthSession signInWithGoogleIdToken(String googleIdToken) throws IOException, InterruptedException {
        String url = "https://identitytoolkit.googleapis.com/v1/accounts:signInWithIdp?key=" + apiKey;

        // postBody debe ir codificado como querystring
        String postBody = "id_token=" + urlEncode(googleIdToken) + "&providerId=google.com";

        String payload = MAPPER.createObjectNode()
            .put("postBody", postBody)
            .put("requestUri", "http://localhost")
            .put("returnSecureToken", true)
            .put("returnIdpCredential", true)
            .toString();

        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw firebaseError(resp.body());
        }

        JsonNode root = MAPPER.readTree(resp.body());
        String uid = root.path("localId").asText();
        String email = root.path("email").asText("");
        String idToken = root.path("idToken").asText();
        String refreshToken = root.path("refreshToken").asText();
        long expiresIn = root.path("expiresIn").asLong();
        long expiresAt = Instant.now().getEpochSecond() + expiresIn - 30;

        if (email.isBlank()) {
            // algunos responses pueden no traer email directamente; intenta inferirlo desde rawUserInfo
            String rawUserInfo = root.path("rawUserInfo").asText("");
            if (!rawUserInfo.isBlank()) {
                try {
                    JsonNode info = MAPPER.readTree(rawUserInfo);
                    email = info.path("email").asText("");
                } catch (Exception ignored) {
                }
            }
        }

        if (email.isBlank()) {
            // Fallback: en última instancia, evita null.
            email = "(google)";
        }

        return new AuthSession(uid, email, idToken, refreshToken, expiresAt);
    }

    public AuthSession signInWithGoogleAccessToken(String googleAccessToken) throws IOException, InterruptedException {
        String url = "https://identitytoolkit.googleapis.com/v1/accounts:signInWithIdp?key=" + apiKey;

        // Usar access_token evita problemas de audiencia del id_token cuando viene de Device Flow.
        String postBody = "access_token=" + urlEncode(googleAccessToken) + "&providerId=google.com";

        String payload = MAPPER.createObjectNode()
            .put("postBody", postBody)
            .put("requestUri", "http://localhost")
            .put("returnSecureToken", true)
            .put("returnIdpCredential", true)
            .toString();

        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw firebaseError(resp.body());
        }

        JsonNode root = MAPPER.readTree(resp.body());
        String uid = root.path("localId").asText();
        String email = root.path("email").asText("");
        String idToken = root.path("idToken").asText();
        String refreshToken = root.path("refreshToken").asText();
        long expiresIn = root.path("expiresIn").asLong();
        long expiresAt = Instant.now().getEpochSecond() + expiresIn - 30;

        if (email.isBlank()) {
            String rawUserInfo = root.path("rawUserInfo").asText("");
            if (!rawUserInfo.isBlank()) {
                try {
                    JsonNode info = MAPPER.readTree(rawUserInfo);
                    email = info.path("email").asText("");
                } catch (Exception ignored) {
                }
            }
        }

        if (email.isBlank()) {
            email = "(google)";
        }

        return new AuthSession(uid, email, idToken, refreshToken, expiresAt);
    }

    private AuthSession signWithEmailPassword(String method, String email, String password) throws IOException, InterruptedException {
        String url = "https://identitytoolkit.googleapis.com/v1/" + method + "?key=" + apiKey;

        String payload = MAPPER.createObjectNode()
            .put("email", email)
            .put("password", password)
            .put("returnSecureToken", true)
            .toString();

        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw firebaseError(resp.body());
        }

        JsonNode root = MAPPER.readTree(resp.body());
        String uid = root.path("localId").asText();
        String idToken = root.path("idToken").asText();
        String refreshToken = root.path("refreshToken").asText();
        long expiresIn = root.path("expiresIn").asLong();
        long expiresAt = Instant.now().getEpochSecond() + expiresIn - 30; // margen

        return new AuthSession(uid, email, idToken, refreshToken, expiresAt);
    }

    public AuthSession refresh(AuthSession session) throws IOException, InterruptedException {
        String url = "https://securetoken.googleapis.com/v1/token?key=" + apiKey;

        String body = "grant_type=refresh_token&refresh_token=" + urlEncode(session.refreshToken());
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw firebaseError(resp.body());
        }

        JsonNode root = MAPPER.readTree(resp.body());
        String uid = root.path("user_id").asText(session.uid());
        String idToken = root.path("id_token").asText();
        String refreshToken = root.path("refresh_token").asText(session.refreshToken());
        long expiresIn = root.path("expires_in").asLong();
        long expiresAt = Instant.now().getEpochSecond() + expiresIn - 30;

        return new AuthSession(uid, session.email(), idToken, refreshToken, expiresAt);
    }

    private static RuntimeException firebaseError(String body) {
        try {
            JsonNode root = MAPPER.readTree(body);
            String msg = root.path("error").path("message").asText(body);
            return new RuntimeException("Firebase Auth error: " + msg);
        } catch (Exception e) {
            return new RuntimeException("Firebase Auth error: " + body);
        }
    }

    private static String urlEncode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
