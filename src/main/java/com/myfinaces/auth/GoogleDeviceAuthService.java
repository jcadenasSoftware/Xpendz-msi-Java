package com.myfinaces.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * @deprecated
 */
public final class GoogleDeviceAuthService {

    @Deprecated
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient http;
    private final String oauthClientId;
    private final String oauthClientSecret;

    public GoogleDeviceAuthService(String oauthClientId, String oauthClientSecret) {
        this.http = HttpClient.newHttpClient();
        this.oauthClientId = oauthClientId;
        this.oauthClientSecret = oauthClientSecret == null ? "" : oauthClientSecret;
    }

    public record DeviceCodeResponse(
        String deviceCode,
        String userCode,
        String verificationUrl,
        String verificationUrlComplete,
        long expiresAtEpochSec,
        int intervalSeconds
    ) {
    }

    public record GoogleTokens(
        String idToken,
        String accessToken,
        long expiresAtEpochSec
    ) {
    }

    public DeviceCodeResponse start() throws IOException, InterruptedException {
        // https://developers.google.com/identity/protocols/oauth2/limited-input-device
        String url = "https://oauth2.googleapis.com/device/code";
        String body = "client_id=" + urlEncode(oauthClientId)
            + "&scope=" + urlEncode("openid email profile");

        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new RuntimeException("Google device code error: " + resp.body());
        }

        JsonNode root = MAPPER.readTree(resp.body());
        String deviceCode = root.path("device_code").asText();
        String userCode = root.path("user_code").asText();
        String verificationUrl = root.path("verification_url").asText(root.path("verification_uri").asText());
        String verificationUrlComplete = root.path("verification_url_complete").asText(root.path("verification_uri_complete").asText(""));
        int interval = root.path("interval").asInt(5);
        long expiresIn = root.path("expires_in").asLong(900);
        long expiresAt = Instant.now().getEpochSecond() + expiresIn;

        return new DeviceCodeResponse(deviceCode, userCode, verificationUrl, verificationUrlComplete, expiresAt, interval);
    }

    public void openVerification(DeviceCodeResponse d) throws IOException {
        String urlToOpen = (d.verificationUrlComplete() != null && !d.verificationUrlComplete().isBlank())
            ? d.verificationUrlComplete()
            : d.verificationUrl();

        if (Desktop.isDesktopSupported()) {
            Desktop.getDesktop().browse(URI.create(urlToOpen));
        } else {
            throw new IOException("Desktop no soportado. Abre manualmente: " + urlToOpen);
        }
    }

    public GoogleTokens waitForTokens(DeviceCodeResponse d) throws IOException, InterruptedException {
        String url = "https://oauth2.googleapis.com/token";

        while (Instant.now().getEpochSecond() < d.expiresAtEpochSec()) {
            String body = "client_id=" + urlEncode(oauthClientId)
                + "&device_code=" + urlEncode(d.deviceCode())
                + "&grant_type=" + urlEncode("urn:ietf:params:oauth:grant-type:device_code");

            if (!oauthClientSecret.isBlank()) {
                body += "&client_secret=" + urlEncode(oauthClientSecret);
            }

            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 == 2) {
                JsonNode root = MAPPER.readTree(resp.body());
                String idToken = root.path("id_token").asText();
                String accessToken = root.path("access_token").asText();
                long expiresIn = root.path("expires_in").asLong(3600);
                long expiresAt = Instant.now().getEpochSecond() + expiresIn - 30;
                if (idToken == null || idToken.isBlank()) {
                    throw new RuntimeException("Google token response sin id_token.");
                }
                return new GoogleTokens(idToken, accessToken, expiresAt);
            }

            // Errores esperables: authorization_pending, slow_down
            String errorCode = null;
            try {
                JsonNode root = MAPPER.readTree(resp.body());
                errorCode = root.path("error").asText();
            } catch (Exception ignored) {
            }

            if ("authorization_pending".equals(errorCode)) {
                Thread.sleep(Math.max(1, d.intervalSeconds()) * 1000L);
                continue;
            }

            if ("slow_down".equals(errorCode)) {
                Thread.sleep((Math.max(1, d.intervalSeconds()) + 3) * 1000L);
                continue;
            }

            if ("access_denied".equals(errorCode)) {
                throw new RuntimeException("Acceso denegado en Google Sign-In.");
            }

            if ("expired_token".equals(errorCode)) {
                throw new RuntimeException("El código de Google expiró. Intenta de nuevo.");
            }

            throw new RuntimeException("Google token error: " + resp.body());
        }

        throw new RuntimeException("Tiempo de espera agotado para Google Sign-In.");
    }

    private static String urlEncode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
