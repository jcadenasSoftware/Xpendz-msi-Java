package com.myfinaces.sync;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

public final class DeviceId {

    private static String cached;

    private DeviceId() {
    }

    public static synchronized String get() {
        if (cached != null && !cached.isBlank()) {
            return cached;
        }

        Path dir = Path.of(System.getProperty("user.home"), ".myfinances");
        Path file = dir.resolve("device-id.txt");

        try {
            if (Files.exists(file)) {
                String s = Files.readString(file, StandardCharsets.UTF_8).trim();
                if (!s.isBlank()) {
                    cached = s;
                    return cached;
                }
            }
        } catch (Exception ignored) {
        }

        String created = UUID.randomUUID().toString();
        try {
            Files.createDirectories(dir);
            Files.writeString(file, created, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }

        cached = created;
        return cached;
    }
}
