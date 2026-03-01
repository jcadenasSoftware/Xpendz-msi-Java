package com.myfinaces.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.awt.Desktop;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class GooglePkceAuthService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient http;
    private final String oauthClientId;
    private final String oauthClientSecret;
    private final int port;

    public record GoogleTokens(
        String idToken,
        String accessToken,
        long expiresAtEpochSec
    ) {
    }

    public GooglePkceAuthService(String oauthClientId, String oauthClientSecret, int port) {
        this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();
        this.oauthClientId = oauthClientId;
        this.oauthClientSecret = oauthClientSecret == null ? "" : oauthClientSecret;
        this.port = port;
    }

    public GoogleTokens authenticate() throws Exception {
        String codeVerifier = generateCodeVerifier();
        String codeChallenge = s256Base64Url(codeVerifier);
        String redirectUri = "http://localhost:" + port + "/callback";

        CompletableFuture<String> codeFuture = new CompletableFuture<>();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/callback", exchange -> handleCallback(exchange, codeFuture));
        server.setExecutor(null);
        server.start();

        try {
            String authUrl = buildAuthUrl(redirectUri, codeChallenge);
            openBrowser(authUrl);

            // Espera hasta 2 minutos a que el usuario termine el login.
            String code = codeFuture.get(120, TimeUnit.SECONDS);
            return exchangeCodeForTokens(code, redirectUri, codeVerifier);
        } finally {
            server.stop(0);
        }
    }

    private String buildAuthUrl(String redirectUri, String codeChallenge) {
        // https://developers.google.com/identity/protocols/oauth2/native-app
        // PKCE: code_challenge + code_verifier
        String scope = "openid email profile";

        return "https://accounts.google.com/o/oauth2/v2/auth"
            + "?client_id=" + urlEncode(oauthClientId)
            + "&redirect_uri=" + urlEncode(redirectUri)
            + "&response_type=code"
            + "&scope=" + urlEncode(scope)
            + "&code_challenge=" + urlEncode(codeChallenge)
            + "&code_challenge_method=S256"
            + "&prompt=select_account";
    }

    private GoogleTokens exchangeCodeForTokens(String code, String redirectUri, String codeVerifier) throws IOException, InterruptedException {
        String url = "https://oauth2.googleapis.com/token";
        String body = "client_id=" + urlEncode(oauthClientId)
            + "&code=" + urlEncode(code)
            + "&code_verifier=" + urlEncode(codeVerifier)
            + "&redirect_uri=" + urlEncode(redirectUri)
            + "&grant_type=authorization_code";

        if (!oauthClientSecret.isBlank()) {
            body += "&client_secret=" + urlEncode(oauthClientSecret);
        }

        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new RuntimeException("Google token error: " + resp.body());
        }

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

    private static void handleCallback(HttpExchange exchange, CompletableFuture<String> codeFuture) throws IOException {
        String query = exchange.getRequestURI().getRawQuery();
        Map<String, String> params = parseQuery(query);

        String code = params.get("code");
        String error = params.get("error");

        String responseHtml;
        int status;

        if (error != null && !error.isBlank()) {
            status = 400;
            responseHtml = "<html><body><h3>Error</h3><p>" + escapeHtml(error) + "</p><p>Ya puedes cerrar esta ventana.</p></body></html>";
            codeFuture.completeExceptionally(new RuntimeException("Google auth error: " + error));
        } else if (code == null || code.isBlank()) {
            status = 400;
            responseHtml = "<html><body><h3>Error</h3><p>No se recibió el parámetro 'code'.</p><p>Ya puedes cerrar esta ventana.</p></body></html>";
            codeFuture.completeExceptionally(new RuntimeException("No se recibió code en callback"));
        } else {
            status = 200;
            responseHtml = "<html><body><h3>Listo</h3><p>Autenticación completada. Ya puedes cerrar esta ventana y volver a la aplicación.</p></body></html>";
            codeFuture.complete(code);
        }

        byte[] bytes = responseHtml.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static Map<String, String> parseQuery(String query) {
        if (query == null || query.isBlank()) {
            return Map.of();
        }
        String[] parts = query.split("&");
        java.util.HashMap<String, String> out = new java.util.HashMap<>();
        for (String p : parts) {
            int idx = p.indexOf('=');
            if (idx <= 0) {
                continue;
            }
            String k = urlDecode(p.substring(0, idx));
            String v = urlDecode(p.substring(idx + 1));
            out.put(k, v);
        }
        return out;
    }

    private static void openBrowser(String url) throws IOException {
        if (!Desktop.isDesktopSupported()) {
            throw new IOException("Desktop no soportado. Abre manualmente: " + url);
        }
        Desktop.getDesktop().browse(URI.create(url));
    }

    private static String generateCodeVerifier() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String s256Base64Url(String codeVerifier) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] hash = md.digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
    }

    private static String urlEncode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String urlDecode(String s) {
        try {
            return java.net.URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return s;
        }
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;");
    }
}
