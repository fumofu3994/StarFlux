package com.fumofu.starflux.config;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.prefs.Preferences;

public final class LauncherData {
    private static final Path DIRECTORY = resolveDirectory();
    private static final Path CONFIG_FILE = DIRECTORY.resolve("config.toml");
    private static final Map<String, String> VALUES = loadValues();

    private LauncherData() {
    }

    public static synchronized String get(String key, String defaultValue) {
        return VALUES.getOrDefault(key, defaultValue);
    }

    public static synchronized void put(String key, String value) {
        VALUES.put(key, value);
        save();
    }

    private static Path resolveDirectory() {
        String configured = System.getProperty("starflux.home");
        if (configured != null && !configured.trim().isEmpty()) {
            return Paths.get(configured).toAbsolutePath().normalize();
        }
        return Paths.get(System.getProperty("user.home"), ".starflux").toAbsolutePath().normalize();
    }

    private static Map<String, String> loadValues() {
        try {
            Files.createDirectories(DIRECTORY);
            if (Files.isRegularFile(CONFIG_FILE)) {
                return parseToml(Files.readString(CONFIG_FILE, StandardCharsets.UTF_8));
            }
            Map<String, String> values = new LinkedHashMap<>();
            Path legacyFile = DIRECTORY.resolve("config.properties");
            if (Files.isRegularFile(legacyFile)) {
                java.util.Properties legacy = new java.util.Properties();
                try (var input = Files.newInputStream(legacyFile)) {
                    legacy.load(input);
                }
                for (String key : legacy.stringPropertyNames()) {
                    values.put(key, legacy.getProperty(key));
                }
            } else {
                migrateLegacyPreferences(values);
            }
            save(values);
            return values;
        } catch (IOException exception) {
            throw new UncheckedIOException("无法读取启动器配置：" + CONFIG_FILE, exception);
        }
    }

    private static Map<String, String> parseToml(String content) throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        boolean launcherTable = false;
        int lineNumber = 0;
        for (String rawLine : content.split("\\R")) {
            lineNumber++;
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.equals("[launcher]")) {
                launcherTable = true;
                continue;
            }
            if (!launcherTable) {
                throw new IOException("config.toml 第 " + lineNumber + " 行位于未知 TOML 表");
            }
            int separator = line.indexOf(" = ");
            if (separator < 0 || !line.startsWith("\"", 0) || !line.endsWith("\"")) {
                throw new IOException("config.toml 第 " + lineNumber + " 行格式无效");
            }
            String key = unescapeTomlString(line.substring(0, separator).trim(), lineNumber);
            String value = unescapeTomlString(line.substring(separator + 3).trim(), lineNumber);
            values.put(key, value);
        }
        return values;
    }

    private static String unescapeTomlString(String quoted, int lineNumber) throws IOException {
        if (quoted.length() < 2 || quoted.charAt(0) != '"' || quoted.charAt(quoted.length() - 1) != '"') {
            throw new IOException("config.toml 第 " + lineNumber + " 行字符串格式无效");
        }
        StringBuilder result = new StringBuilder();
        for (int index = 1; index < quoted.length() - 1; index++) {
            char character = quoted.charAt(index);
            if (character != '\\') {
                if (character == '"') {
                    throw new IOException("config.toml 第 " + lineNumber + " 行包含未转义引号");
                }
                result.append(character);
                continue;
            }
            if (++index >= quoted.length() - 1) {
                throw new IOException("config.toml 第 " + lineNumber + " 行转义无效");
            }
            switch (quoted.charAt(index)) {
                case '"': result.append('"'); break;
                case '\\': result.append('\\'); break;
                case 'b': result.append('\b'); break;
                case 't': result.append('\t'); break;
                case 'n': result.append('\n'); break;
                case 'f': result.append('\f'); break;
                case 'r': result.append('\r'); break;
                default: throw new IOException("config.toml 第 " + lineNumber + " 行包含不支持的转义");
            }
        }
        return result.toString();
    }

    private static void migrateLegacyPreferences(Map<String, String> values) {
        Preferences legacyPreferences = Preferences.userRoot().node("com/fumofu/starflux");
        copyPreference(values, legacyPreferences, "download-mirror");
        copyPreference(values, legacyPreferences, "game-directory");
        copyPreference(values, legacyPreferences, "offline-accounts");
        copyPreference(values, legacyPreferences, "selected-offline-account");
    }

    private static void copyPreference(Map<String, String> values, Preferences preferences, String key) {
        String value = preferences.get(key, null);
        if (value != null) {
            values.put(key, value);
        }
    }

    private static void save() {
        save(VALUES);
    }

    private static void save(Map<String, String> values) {
        Path temporary = CONFIG_FILE.resolveSibling(CONFIG_FILE.getFileName() + ".tmp");
        try {
            try (OutputStream output = Files.newOutputStream(temporary,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                StringBuilder toml = new StringBuilder("# StarFlux MC Launcher settings\n\n[launcher]\n");
                for (Map.Entry<String, String> entry : values.entrySet()) {
                    toml.append('"').append(escapeTomlString(entry.getKey())).append("\" = \"")
                            .append(escapeTomlString(entry.getValue())).append("\"\n");
                }
                output.write(toml.toString().getBytes(StandardCharsets.UTF_8));
            }
            try {
                Files.move(temporary, CONFIG_FILE, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, CONFIG_FILE, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("无法保存启动器配置：" + CONFIG_FILE, exception);
        }
    }

    private static String escapeTomlString(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\b", "\\b").replace("\t", "\\t").replace("\n", "\\n")
                .replace("\f", "\\f").replace("\r", "\\r");
    }
}
