package com.myfinaces.config;

import java.io.FileInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Properties;

public final class AppConfig {

    private final String firebaseApiKey;
    private final String firebaseProjectId;
    private final String googleOAuthClientId;
    private final String googleOAuthClientSecret;

    private AppConfig(String firebaseApiKey, String firebaseProjectId, String googleOAuthClientId, String googleOAuthClientSecret) {
        this.firebaseApiKey = Objects.requireNonNull(firebaseApiKey);
        this.firebaseProjectId = Objects.requireNonNull(firebaseProjectId);
        this.googleOAuthClientId = Objects.requireNonNullElse(googleOAuthClientId, "");
        this.googleOAuthClientSecret = Objects.requireNonNullElse(googleOAuthClientSecret, "");
    }

    public String firebaseApiKey() {
        return firebaseApiKey;
    }

    public String firebaseProjectId() {
        return firebaseProjectId;
    }

    public String googleOAuthClientId() {
        return googleOAuthClientId;
    }

    public String googleOAuthClientSecret() {
        return googleOAuthClientSecret;
    }

    public static AppConfig loadDefault() throws IOException {
        List<Path> tried = new ArrayList<>();

        String override = System.getProperty("myfinanzas.config");
        if (override != null && !override.isBlank()) {
            tried.add(Path.of(override.trim()));
        }

        tried.add(Path.of(System.getProperty("user.home"), ".myfinances", "config", "app.properties"));

        Path jarDir = tryResolveJarDir();
        if (jarDir != null) {
            tried.add(jarDir.resolve("config").resolve("app.properties"));
            Path parent = jarDir.getParent();
            if (parent != null) {
                tried.add(parent.resolve("config").resolve("app.properties"));
            }
        }

        tried.add(Path.of("config", "app.properties"));

        Path configPath = null;
        for (Path p : tried) {
            if (p != null && Files.exists(p)) {
                configPath = p;
                break;
            }
        }

        if (configPath == null) {
            StringBuilder sb = new StringBuilder();
            sb.append("No se encontró app.properties. Rutas intentadas:\n");
            for (Path p : tried) {
                if (p == null) continue;
                sb.append("- ").append(p.toAbsolutePath()).append("\n");
            }
            sb.append("\nSugerencia: crea el archivo en ")
              .append(Path.of(System.getProperty("user.home"), ".myfinances", "config", "app.properties").toAbsolutePath());
            throw new IOException(sb.toString());
        }

        Properties props = new Properties();
        try (FileInputStream in = new FileInputStream(configPath.toFile())) {
            props.load(in);
        }

        String apiKey = require(props, "firebase.apiKey");
        String projectId = require(props, "firebase.projectId");
        String oauthClientId = props.getProperty("google.oauthClientId", "").trim();
        String oauthClientSecret = props.getProperty("google.oauthClientSecret", "").trim();

        return new AppConfig(apiKey, projectId, oauthClientId, oauthClientSecret);
    }

    private static String require(Properties props, String key) throws IOException {
        String val = props.getProperty(key);
        if (val == null || val.isBlank()) {
            throw new IOException("Falta la propiedad requerida '" + key + "' en config/app.properties");
        }
        return val.trim();
    }

    private static Path tryResolveJarDir() {
        try {
            URI uri = AppConfig.class.getProtectionDomain().getCodeSource().getLocation().toURI();
            Path p = Path.of(uri);
            if (Files.isDirectory(p)) {
                return p;
            }
            Path parent = p.getParent();
            return parent;
        } catch (URISyntaxException | RuntimeException ex) {
            return null;
        }
    }
}
