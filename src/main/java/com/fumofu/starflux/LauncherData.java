package com.fumofu.starflux;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Properties;
import java.util.prefs.Preferences;

final class LauncherData {
    private static final Path DIRECTORY = resolveDirectory();
    private static final Path CONFIG_FILE = DIRECTORY.resolve("config.properties");
    private static final Properties PROPERTIES = loadProperties();

    private LauncherData() {
    }

    static synchronized String get(String key, String defaultValue) {
        return PROPERTIES.getProperty(key, defaultValue);
    }

    static synchronized void put(String key, String value) {
        PROPERTIES.setProperty(key, value);
        save();
    }

    private static Path resolveDirectory() {
        String configured = System.getProperty("starflux.home");
        if (configured != null && !configured.trim().isEmpty()) {
            return Paths.get(configured).toAbsolutePath().normalize();
        }
        return Paths.get(System.getProperty("user.home"), ".starflux").toAbsolutePath().normalize();
    }

    private static Properties loadProperties() {
        Properties properties = new Properties();
        try {
            Files.createDirectories(DIRECTORY);
            if (Files.isRegularFile(CONFIG_FILE)) {
                try (InputStream input = Files.newInputStream(CONFIG_FILE)) {
                    properties.load(input);
                }
            } else {
                migrateLegacyPreferences(properties);
                save(properties);
            }
            return properties;
        } catch (IOException exception) {
            throw new UncheckedIOException("无法读取启动器配置：" + CONFIG_FILE, exception);
        }
    }

    private static void migrateLegacyPreferences(Properties properties) {
        Preferences launcherPreferences = Preferences.userNodeForPackage(LauncherController.class);
        copyPreference(properties, launcherPreferences, "download-mirror");
        copyPreference(properties, launcherPreferences, "game-directory");

        Preferences accountPreferences = Preferences.userNodeForPackage(OfflineAccountStore.class);
        copyPreference(properties, accountPreferences, "offline-accounts");
        copyPreference(properties, accountPreferences, "selected-offline-account");
    }

    private static void copyPreference(Properties properties, Preferences preferences, String key) {
        String value = preferences.get(key, null);
        if (value != null) {
            properties.setProperty(key, value);
        }
    }

    private static void save() {
        save(PROPERTIES);
    }

    private static void save(Properties properties) {
        Path temporary = CONFIG_FILE.resolveSibling(CONFIG_FILE.getFileName() + ".tmp");
        try {
            try (OutputStream output = Files.newOutputStream(temporary,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                properties.store(output, "StarFlux MC Launcher settings");
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
}
