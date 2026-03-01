package com.myfinaces;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

public final class AppVersion {

    private AppVersion() {
    }

    public static String getVersion() {
        String v = null;

        try {
            v = System.getProperty("jpackage.app-version");
        } catch (Exception ignored) {
        }
        if (v != null && !v.isBlank()) {
            return v.trim();
        }

        try {
            Package p = AppVersion.class.getPackage();
            v = p == null ? null : p.getImplementationVersion();
        } catch (Exception ignored) {
        }
        if (v != null && !v.isBlank()) {
            return v.trim();
        }

        v = readMavenPomPropertiesVersion();
        if (v != null && !v.isBlank()) {
            return v.trim();
        }

        v = readPomXmlVersion();
        if (v != null && !v.isBlank()) {
            return v.trim();
        }

        return null;
    }

    public static String withVersion(String title) {
        String t = title == null ? "" : title.trim();
        String v = getVersion();
        if (v == null || v.isBlank()) {
            return t;
        }
        String marker = " v";
        if (!t.isBlank() && t.toLowerCase(Locale.ROOT).contains(marker)) {
            return t;
        }
        if (t.isBlank()) {
            return "v" + v;
        }
        return t + " v" + v;
    }

    private static String readMavenPomPropertiesVersion() {
        String resource = "/META-INF/maven/com.myfinances/MyFinances/pom.properties";
        try (InputStream in = AppVersion.class.getResourceAsStream(resource)) {
            if (in == null) {
                return null;
            }
            Properties props = new Properties();
            props.load(in);
            String v = props.getProperty("version");
            return v == null ? null : v.trim();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String readPomXmlVersion() {
        try {
            Path pom = findPomXml();
            if (pom == null) {
                return null;
            }
            String xml = Files.readString(pom, StandardCharsets.UTF_8);

            int g = xml.indexOf("<groupId>com.myfinances</groupId>");
            if (g < 0) {
                return null;
            }
            int a = xml.indexOf("<artifactId>MyFinances</artifactId>", g);
            if (a < 0) {
                return null;
            }
            int vTag = xml.indexOf("<version>", a);
            if (vTag < 0) {
                return null;
            }
            int vEnd = xml.indexOf("</version>", vTag);
            if (vEnd < 0) {
                return null;
            }
            String v = xml.substring(vTag + "<version>".length(), vEnd);
            return v.trim();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Path findPomXml() {
        try {
            Path dir = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath();
            for (int i = 0; i < 12 && dir != null; i++) {
                Path pom = dir.resolve("pom.xml");
                if (Files.exists(pom)) {
                    return pom;
                }
                dir = dir.getParent();
            }
        } catch (Exception ignored) {
        }

        try {
            var loc = AppVersion.class.getProtectionDomain().getCodeSource().getLocation();
            if (loc != null) {
                Path dir = Path.of(loc.toURI()).toAbsolutePath();
                if (!Files.isDirectory(dir)) {
                    dir = dir.getParent();
                }
                for (int i = 0; i < 12 && dir != null; i++) {
                    Path pom = dir.resolve("pom.xml");
                    if (Files.exists(pom)) {
                        return pom;
                    }
                    dir = dir.getParent();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
