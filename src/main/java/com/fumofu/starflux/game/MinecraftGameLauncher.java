package com.fumofu.starflux.game;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class MinecraftGameLauncher {
    public Process launch(String versionId, String playerName, Path gameDirectory) throws IOException {
        return launch(versionId, playerName, gameDirectory, 4);
    }

    public Process launch(String versionId, String playerName, Path gameDirectory, int maxMemoryGb)
            throws IOException {
        if (versionId == null || !versionId.matches("[A-Za-z0-9._-]+")) {
            throw new IOException("无效的 Minecraft 版本号：" + versionId);
        }
        if (playerName == null || !playerName.matches("[A-Za-z0-9_]{3,16}")) {
            throw new IOException("离线账号名必须为 3 到 16 位英文字母、数字或下划线");
        }
        if (maxMemoryGb < 1 || maxMemoryGb > 64) {
            throw new IOException("最大内存必须在 1 到 64 GB 之间");
        }

        JsonObject version = loadVersion(versionId, gameDirectory, new HashSet<String>(), 0);
        String jarVersion = getString(version, "jar", versionId);
        Path clientJar = gameDirectory.resolve("versions").resolve(jarVersion).resolve(jarVersion + ".jar");
        requireFile(clientJar);

        Path nativesDirectory = gameDirectory.resolve("versions").resolve(versionId).resolve("natives");
        List<Path> classpath = collectLibraries(version, gameDirectory, nativesDirectory);
        classpath.add(clientJar);
        String classpathValue = joinClasspath(classpath);

        Map<String, String> placeholders = new HashMap<String, String>();
        placeholders.put("auth_player_name", playerName);
        placeholders.put("version_name", versionId);
        placeholders.put("game_directory", gameDirectory.toAbsolutePath().toString());
        placeholders.put("assets_root", gameDirectory.resolve("assets").toAbsolutePath().toString());
        JsonObject assetIndex = version.getAsJsonObject("assetIndex");
        placeholders.put("assets_index_name", assetIndex == null
                ? getString(version, "assets", versionId) : getString(assetIndex, "id", versionId));
        placeholders.put("auth_uuid", UUID.nameUUIDFromBytes(
                ("OfflinePlayer:" + playerName).getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .toString().replace("-", ""));
        placeholders.put("auth_access_token", "0");
        placeholders.put("auth_session", "0");
        placeholders.put("auth_xuid", "0");
        placeholders.put("clientid", "0");
        placeholders.put("user_properties", "{}");
        placeholders.put("user_type", "legacy");
        placeholders.put("version_type", getString(version, "type", "release"));
        placeholders.put("launcher_name", "StarFlux MC Launcher");
        placeholders.put("launcher_version", "1.0");
        placeholders.put("natives_directory", nativesDirectory.toAbsolutePath().toString());
        placeholders.put("classpath", classpathValue);
        placeholders.put("classpath_separator", File.pathSeparator);
        placeholders.put("library_directory", gameDirectory.resolve("libraries").toAbsolutePath().toString());

        List<String> command = new ArrayList<String>();
        command.add(javaExecutable().toString());
        JsonObject arguments = version.getAsJsonObject("arguments");
        if (arguments != null && arguments.has("jvm")) {
            addArguments(command, arguments.getAsJsonArray("jvm"), placeholders);
            if (!command.contains("-cp") && !command.contains("-classpath")) {
                command.add("-cp");
                command.add(classpathValue);
            }
        } else {
            command.add("-Djava.library.path=" + nativesDirectory.toAbsolutePath());
            command.add("-cp");
            command.add(classpathValue);
        }
        applyMaximumMemory(command, maxMemoryGb);

        String mainClass = getString(version, "mainClass", null);
        if (mainClass == null) {
            throw new IOException("版本配置中缺少主类");
        }
        command.add(mainClass);
        if (arguments != null && arguments.has("game")) {
            addArguments(command, arguments.getAsJsonArray("game"), placeholders);
        } else {
            String legacyArguments = getString(version, "minecraftArguments", "");
            for (String argument : legacyArguments.split("\\s+")) {
                if (!argument.isEmpty()) {
                    command.add(replacePlaceholders(argument, placeholders));
                }
            }
        }

        Path logsDirectory = gameDirectory.resolve("logs");
        Files.createDirectories(logsDirectory);
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(gameDirectory.toFile());
        processBuilder.redirectErrorStream(true);
        processBuilder.redirectOutput(ProcessBuilder.Redirect.appendTo(
                logsDirectory.resolve("starflux-launcher.log").toFile()));
        try {
            return processBuilder.start();
        } catch (IOException exception) {
            throw new IOException("启动 Minecraft 失败，请确认已安装合适的 Java 版本：" + exception.getMessage(), exception);
        }
    }

    private static void applyMaximumMemory(List<String> command, int maxMemoryGb) {
        for (int index = 1; index < command.size(); index++) {
            String argument = command.get(index);
            if ("-Xmx".equals(argument)) {
                command.remove(index);
                if (index < command.size()) {
                    command.remove(index);
                }
                index--;
            } else if (argument.startsWith("-Xmx")) {
                command.remove(index--);
            }
        }
        command.add(1, "-Xmx" + maxMemoryGb + "G");
    }

    private JsonObject loadVersion(String versionId, Path gameDirectory, Set<String> visited, int depth)
            throws IOException {
        if (depth > 16 || !visited.add(versionId)) {
            throw new IOException("游戏版本继承关系无效：" + versionId);
        }
        Path path = gameDirectory.resolve("versions").resolve(versionId).resolve(versionId + ".json");
        if (!Files.isRegularFile(path)) {
            throw new IOException("找不到版本配置：" + path);
        }

        JsonObject current;
        try {
            current = new JsonParser().parse(new String(Files.readAllBytes(path), "UTF-8")).getAsJsonObject();
        } catch (RuntimeException exception) {
            throw new IOException("版本配置文件无效：" + path, exception);
        }

        String parentId = getString(current, "inheritsFrom", null);
        if (parentId == null) {
            current.addProperty("jar", getString(current, "jar", versionId));
            return current;
        }
        if (!parentId.matches("[A-Za-z0-9._-]+")) {
            throw new IOException("版本继承配置包含无效版本号：" + parentId);
        }

        JsonObject parent = loadVersion(parentId, gameDirectory, visited, depth + 1);
        JsonObject merged = mergeVersions(parent, current);
        merged.addProperty("id", versionId);
        merged.addProperty("jar", getString(current, "jar", getString(parent, "jar", parentId)));
        return merged;
    }

    private static JsonObject mergeVersions(JsonObject parent, JsonObject child) {
        JsonObject merged = parent.deepCopy();
        for (Map.Entry<String, JsonElement> entry : child.entrySet()) {
            String key = entry.getKey();
            JsonElement value = entry.getValue();
            if ("libraries".equals(key) && value.isJsonArray()) {
                JsonArray libraries = merged.has(key) && merged.get(key).isJsonArray()
                        ? merged.getAsJsonArray(key).deepCopy() : new JsonArray();
                for (JsonElement library : value.getAsJsonArray()) {
                    libraries.add(library.deepCopy());
                }
                merged.add(key, libraries);
            } else if ("arguments".equals(key) && value.isJsonObject()) {
                JsonObject parentArguments = merged.has(key) && merged.get(key).isJsonObject()
                        ? merged.getAsJsonObject(key) : null;
                merged.add(key, mergeArguments(parentArguments, value.getAsJsonObject()));
            } else {
                merged.add(key, value.deepCopy());
            }
        }
        return merged;
    }

    private static JsonObject mergeArguments(JsonObject parent, JsonObject child) {
        JsonObject merged = parent == null ? new JsonObject() : parent.deepCopy();
        for (Map.Entry<String, JsonElement> entry : child.entrySet()) {
            String key = entry.getKey();
            JsonElement value = entry.getValue();
            if (value.isJsonArray() && merged.has(key) && merged.get(key).isJsonArray()) {
                JsonArray combined = merged.getAsJsonArray(key).deepCopy();
                for (JsonElement argument : value.getAsJsonArray()) {
                    combined.add(argument.deepCopy());
                }
                merged.add(key, combined);
            } else {
                merged.add(key, value.deepCopy());
            }
        }
        return merged;
    }

    private List<Path> collectLibraries(JsonObject version, Path gameDirectory, Path nativesDirectory)
            throws IOException {
        List<Path> classpath = new ArrayList<Path>();
        JsonArray libraries = version.getAsJsonArray("libraries");
        if (libraries == null) {
            return classpath;
        }
        Files.createDirectories(nativesDirectory);
        for (JsonElement element : libraries) {
            JsonObject library = element.getAsJsonObject();
            if (!isLibraryAllowed(library)) {
                continue;
            }
            JsonObject downloads = library.getAsJsonObject("downloads");
            JsonObject artifact = downloads == null ? null : downloads.getAsJsonObject("artifact");
            if (artifact != null) {
                String relativePath = getString(artifact, "path", null);
                if (relativePath != null) {
                    Path jar = resolveInside(gameDirectory.resolve("libraries"), relativePath);
                    requireFile(jar);
                    classpath.add(jar);
                }
            } else {
                Path legacyJar = legacyLibraryPath(library, gameDirectory, null);
                requireFile(legacyJar);
                classpath.add(legacyJar);
            }

            String nativeKey = getNativeKey(library);
            if (nativeKey == null) {
                continue;
            }
            JsonObject classifiers = downloads == null ? null : downloads.getAsJsonObject("classifiers");
            JsonObject nativeArtifact = classifiers == null ? null : classifiers.getAsJsonObject(nativeKey);
            Path nativeJar;
            if (nativeArtifact != null) {
                String relativePath = getString(nativeArtifact, "path", null);
                if (relativePath == null) {
                    continue;
                }
                nativeJar = resolveInside(gameDirectory.resolve("libraries"), relativePath);
            } else {
                nativeJar = legacyLibraryPath(library, gameDirectory, nativeKey);
            }
            if (Files.isRegularFile(nativeJar)) {
                extractNatives(nativeJar, nativesDirectory, library);
            }
        }
        return classpath;
    }

    private static void extractNatives(Path archive, Path destination, JsonObject library) throws IOException {
        List<String> exclusions = new ArrayList<String>();
        JsonObject extract = library.getAsJsonObject("extract");
        JsonArray exclude = extract == null ? null : extract.getAsJsonArray("exclude");
        if (exclude != null) {
            for (JsonElement pattern : exclude) {
                exclusions.add(pattern.getAsString());
            }
        }
        Path normalizedDestination = destination.toAbsolutePath().normalize();
        try (InputStream input = Files.newInputStream(archive);
             ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory() || isExcluded(entry.getName(), exclusions)) {
                    continue;
                }
                Path output = normalizedDestination.resolve(entry.getName()).normalize();
                if (!output.startsWith(normalizedDestination)) {
                    throw new IOException("原生库包含无效路径：" + entry.getName());
                }
                Files.createDirectories(output.getParent());
                Files.copy(zip, output, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static boolean isExcluded(String name, List<String> exclusions) {
        for (String exclusion : exclusions) {
            if ((exclusion.endsWith("/") && name.startsWith(exclusion)) || name.equals(exclusion)) {
                return true;
            }
        }
        return false;
    }

    private static Path legacyLibraryPath(JsonObject library, Path gameDirectory, String classifier)
            throws IOException {
        String coordinate = getString(library, "name", null);
        if (coordinate == null) {
            throw new IOException("依赖库缺少名称");
        }
        String extension = "jar";
        int separator = coordinate.indexOf('@');
        if (separator >= 0) {
            extension = coordinate.substring(separator + 1);
            coordinate = coordinate.substring(0, separator);
        }
        String[] parts = coordinate.split(":");
        if (parts.length < 3 || parts.length > 4) {
            throw new IOException("无效的 Maven 依赖坐标：" + coordinate);
        }
        String actualClassifier = classifier == null && parts.length == 4 ? parts[3] : classifier;
        String fileName = parts[1] + "-" + parts[2]
                + (actualClassifier == null ? "" : "-" + actualClassifier) + "." + extension;
        return resolveInside(gameDirectory.resolve("libraries"),
                parts[0].replace('.', '/') + "/" + parts[1] + "/" + parts[2] + "/" + fileName);
    }

    private static String getNativeKey(JsonObject library) {
        JsonObject natives = library.getAsJsonObject("natives");
        if (natives == null) {
            return null;
        }
        JsonElement element = natives.get(osName());
        return element == null || element.isJsonNull()
                ? null : element.getAsString().replace("${arch}", nativeArchitecture());
    }

    private static boolean isLibraryAllowed(JsonObject library) {
        JsonArray rules = library.getAsJsonArray("rules");
        if (rules == null) {
            return true;
        }
        boolean allowed = false;
        for (JsonElement element : rules) {
            JsonObject rule = element.getAsJsonObject();
            if (ruleMatches(rule)) {
                allowed = "allow".equals(getString(rule, "action", "disallow"));
            }
        }
        return allowed;
    }

    private static boolean ruleMatches(JsonObject rule) {
        JsonObject os = rule.getAsJsonObject("os");
        if (os != null) {
            String name = getString(os, "name", null);
            if (name != null && !name.equals(osName())) {
                return false;
            }
            String arch = getString(os, "arch", null);
            if (arch != null && !Pattern.matches(arch, System.getProperty("os.arch"))) {
                return false;
            }
            String osVersion = getString(os, "version", null);
            if (osVersion != null && !Pattern.matches(osVersion, System.getProperty("os.version"))) {
                return false;
            }
        }
        JsonObject features = rule.getAsJsonObject("features");
        if (features != null) {
            for (Map.Entry<String, JsonElement> feature : features.entrySet()) {
                if (feature.getValue().getAsBoolean()) {
                    return false;
                }
            }
        }
        return true;
    }

    private static void addArguments(List<String> command, JsonArray arguments, Map<String, String> placeholders) {
        for (JsonElement argument : arguments) {
            if (argument.isJsonPrimitive()) {
                command.add(replacePlaceholders(argument.getAsString(), placeholders));
            } else if (argument.isJsonObject()) {
                JsonObject conditional = argument.getAsJsonObject();
                if (!isArgumentAllowed(conditional)) {
                    continue;
                }
                JsonElement value = conditional.get("value");
                if (value == null) {
                    continue;
                }
                if (value.isJsonArray()) {
                    for (JsonElement item : value.getAsJsonArray()) {
                        command.add(replacePlaceholders(item.getAsString(), placeholders));
                    }
                } else {
                    command.add(replacePlaceholders(value.getAsString(), placeholders));
                }
            }
        }
    }

    private static boolean isArgumentAllowed(JsonObject argument) {
        JsonArray rules = argument.getAsJsonArray("rules");
        if (rules == null) {
            return true;
        }
        boolean allowed = false;
        for (JsonElement element : rules) {
            JsonObject rule = element.getAsJsonObject();
            if (ruleMatches(rule)) {
                allowed = "allow".equals(getString(rule, "action", "disallow"));
            }
        }
        return allowed;
    }

    private static String replacePlaceholders(String value, Map<String, String> placeholders) {
        String result = value;
        for (Map.Entry<String, String> placeholder : placeholders.entrySet()) {
            result = result.replace("${" + placeholder.getKey() + "}", placeholder.getValue());
        }
        return result;
    }

    private static String joinClasspath(List<Path> paths) {
        StringBuilder result = new StringBuilder();
        for (Path path : paths) {
            if (result.length() > 0) {
                result.append(File.pathSeparator);
            }
            result.append(path.toAbsolutePath());
        }
        return result.toString();
    }

    private static Path javaExecutable() throws IOException {
        String executable = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")
                ? "java.exe" : "java";
        Path java = Paths.get(System.getProperty("java.home"), "bin", executable);
        if (!Files.isRegularFile(java)) {
            throw new IOException("找不到 Java 可执行文件：" + java);
        }
        return java;
    }

    private static void requireFile(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            throw new IOException("缺少游戏依赖文件，请重新安装对应版本：" + path);
        }
    }

    private static Path resolveInside(Path base, String relativePath) throws IOException {
        Path normalizedBase = base.toAbsolutePath().normalize();
        Path resolved = normalizedBase.resolve(relativePath).normalize();
        if (!resolved.startsWith(normalizedBase)) {
            throw new IOException("依赖路径超出游戏目录：" + relativePath);
        }
        return resolved;
    }

    private static String getString(JsonObject object, String key, String fallback) {
        JsonElement element = object.get(key);
        return element == null || element.isJsonNull() ? fallback : element.getAsString();
    }

    private static String osName() {
        String name = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        if (name.contains("win")) {
            return "windows";
        }
        if (name.contains("mac")) {
            return "osx";
        }
        return "linux";
    }

    private static String nativeArchitecture() {
        String architecture = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        return architecture.contains("64") || architecture.equals("aarch64") ? "64" : "32";
    }
}
