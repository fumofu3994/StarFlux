package com.fumofu.starflux.game;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LoaderInstaller {
    private static final String FABRIC_META = "https://meta.fabricmc.net";
    private static final String QUILT_META = "https://meta.quiltmc.org";
    private static final String FORGE_METADATA =
            "https://maven.minecraftforge.net/net/minecraftforge/forge/maven-metadata.xml";
    private static final String NEOFORGE_METADATA =
            "https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml";

    public List<String> fetchVersions(LoaderType type, String minecraftVersion) throws IOException {
        requireVersion(minecraftVersion);
        switch (type) {
            case FABRIC:
                return fetchJsonLoaderVersions(
                        FABRIC_META + "/v2/versions/loader/" + minecraftVersion, "loader");
            case QUILT:
                return fetchJsonLoaderVersions(QUILT_META + "/v3/versions/loader/" + minecraftVersion, "loader");
            case FORGE:
                return fetchForgeVersions(minecraftVersion);
            case NEOFORGE:
                return fetchNeoForgeVersions(minecraftVersion);
            default:
                throw new IOException("不支持的模组加载器：" + type);
        }
    }

    public void install(LoaderType type, String minecraftVersion, String loaderVersion, Path gameDirectory,
                       ProgressListener listener) throws Exception {
        requireVersion(minecraftVersion);
        requireVersion(loaderVersion);
        Path vanillaVersion = gameDirectory.resolve("versions").resolve(minecraftVersion);
        if (!Files.isRegularFile(vanillaVersion.resolve(minecraftVersion + ".json"))
                || !Files.isRegularFile(vanillaVersion.resolve(minecraftVersion + ".jar"))) {
            throw new IOException("请先下载并安装 Minecraft " + minecraftVersion);
        }

        Files.createDirectories(gameDirectory.resolve("versions"));
        Files.createDirectories(gameDirectory.resolve("logs"));
        listener.onProgress("正在准备 " + type + " 安装器...");

        List<String> command = new ArrayList<String>();
        command.add(javaExecutable().toString());
        Path installerJar = downloadInstaller(type, loaderVersion, gameDirectory);
        command.add("-jar");
        command.add(installerJar.toAbsolutePath().toString());

        switch (type) {
            case FABRIC:
                command.add("client");
                command.add("-noprofile");
                command.add("-dir");
                command.add(gameDirectory.toAbsolutePath().toString());
                command.add("-mcversion");
                command.add(minecraftVersion);
                command.add("-loader");
                command.add(loaderVersion);
                break;
            case QUILT:
                command.add("install");
                command.add("client");
                command.add(minecraftVersion);
                command.add(loaderVersion);
                command.add("--install-dir=" + gameDirectory.toAbsolutePath());
                break;
            case FORGE:
            case NEOFORGE:
                command.add("--installClient");
                command.add(gameDirectory.toAbsolutePath().toString());
                break;
            default:
                throw new IOException("不支持的模组加载器：" + type);
        }

        listener.onProgress("正在运行 " + type + " 安装器，这可能需要几分钟...");
        Path log = gameDirectory.resolve("logs")
                .resolve("starflux-" + type.name().toLowerCase() + "-installer.log");
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(gameDirectory.toFile());
        processBuilder.redirectErrorStream(true);
        processBuilder.redirectOutput(log.toFile());
        Process process = processBuilder.start();
        int exitCode;
        try {
            exitCode = process.waitFor();
        } catch (InterruptedException exception) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException(type + " 安装已取消", exception);
        }
        if (exitCode != 0) {
            throw new IOException(type + " 安装器退出代码为 " + exitCode + "，详细日志：" + log);
        }

        List<Path> profiles = findInstalledProfiles(gameDirectory, minecraftVersion, type);
        if (profiles.isEmpty()) {
            throw new IOException(type + " 安装器已退出，但没有找到对应的游戏配置，请查看日志：" + log);
        }
        listener.onProgress(type + " 安装完成：" + profiles.get(0).getFileName());
    }

    private static Path downloadInstaller(LoaderType type, String loaderVersion, Path gameDirectory)
            throws IOException {
        String version = installerVersion(type, loaderVersion);
        String relativePath;
        switch (type) {
            case FABRIC:
                relativePath = "net/fabricmc/fabric-installer/" + version
                        + "/fabric-installer-" + version + ".jar";
                return download("https://maven.fabricmc.net/" + relativePath,
                        gameDirectory.resolve("libraries/starflux/installers/fabric-installer-" + version + ".jar"));
            case QUILT:
                relativePath = "org/quiltmc/quilt-installer/" + version
                        + "/quilt-installer-" + version + ".jar";
                return download("https://maven.quiltmc.org/repository/release/" + relativePath,
                        gameDirectory.resolve("libraries/starflux/installers/quilt-installer-" + version + ".jar"));
            case FORGE:
                String forgeVersion = loaderVersion.substring(loaderVersion.indexOf('-') + 1);
                relativePath = "net/minecraftforge/forge/" + loaderVersion
                        + "/forge-" + loaderVersion + "-installer.jar";
                return download("https://maven.minecraftforge.net/" + relativePath,
                        gameDirectory.resolve("libraries/starflux/installers/forge-" + forgeVersion + "-installer.jar"));
            case NEOFORGE:
                relativePath = "net/neoforged/neoforge/" + loaderVersion
                        + "/neoforge-" + loaderVersion + "-installer.jar";
                return download("https://maven.neoforged.net/releases/" + relativePath,
                        gameDirectory.resolve("libraries/starflux/installers/neoforge-" + loaderVersion + "-installer.jar"));
            default:
                throw new IOException("不支持的模组加载器：" + type);
        }
    }

    private static String installerVersion(LoaderType type, String loaderVersion) throws IOException {
        switch (type) {
            case FABRIC:
                JsonArray fabricInstallers = parseJsonArray(readUrl(FABRIC_META + "/v2/versions/installer"));
                if (fabricInstallers.isEmpty()) {
                    throw new IOException("Fabric Installer 版本列表为空");
                }
                return fabricInstallers.get(0).getAsJsonObject().get("version").getAsString();
            case QUILT:
                return latestMavenVersion("https://maven.quiltmc.org/repository/release/"
                        + "org/quiltmc/quilt-installer/maven-metadata.xml");
            default:
                return loaderVersion;
        }
    }

    private static List<String> fetchJsonLoaderVersions(String url, String key) throws IOException {
        JsonArray versions = parseJsonArray(readUrl(url));
        Set<String> unique = new LinkedHashSet<String>();
        for (JsonElement element : versions) {
            JsonObject version = element.getAsJsonObject().getAsJsonObject(key);
            if (version != null && version.has("version")) {
                unique.add(version.get("version").getAsString());
            }
        }
        return new ArrayList<String>(unique);
    }

    private static List<String> fetchForgeVersions(String minecraftVersion) throws IOException {
        String metadata = new String(readUrl(FORGE_METADATA), "UTF-8");
        Matcher matcher = Pattern.compile("<version>\\s*([^<]+?)\\s*</version>").matcher(metadata);
        Set<String> versions = new LinkedHashSet<String>();
        String prefix = minecraftVersion + "-";
        while (matcher.find()) {
            String version = matcher.group(1).trim();
            if (version.startsWith(prefix)
                    && version.substring(prefix.length()).matches("[A-Za-z0-9._+-]+")) {
                versions.add(version);
            }
        }
        List<String> result = new ArrayList<String>(versions);
        Collections.sort(result, new Comparator<String>() {
            @Override
            public int compare(String left, String right) {
                return compareVersions(right.substring(prefix.length()), left.substring(prefix.length()));
            }
        });
        if (result.isEmpty()) {
            throw new IOException("Forge 尚未为 Minecraft " + minecraftVersion + " 发布推荐或最新版本");
        }
        return result;
    }

    private static List<String> fetchNeoForgeVersions(String minecraftVersion) throws IOException {
        String metadata = new String(readUrl(NEOFORGE_METADATA), "UTF-8");
        Matcher matcher = Pattern.compile("<version>([^<]+)</version>").matcher(metadata);
        String prefix = neoForgeVersionPrefix(minecraftVersion);
        List<String> versions = new ArrayList<String>();
        while (matcher.find()) {
            String version = matcher.group(1).trim();
            boolean modernVersion = version.startsWith(prefix);
            boolean legacyVersion = version.startsWith(minecraftVersion + "-");
            if ((modernVersion || legacyVersion) && version.matches("[0-9][A-Za-z0-9.+_-]*")) {
                versions.add(version);
            }
        }
        Collections.sort(versions, new Comparator<String>() {
            @Override
            public int compare(String left, String right) {
                return compareVersions(right, left);
            }
        });
        if (versions.isEmpty()) {
            throw new IOException("NeoForge 尚未找到适用于 Minecraft " + minecraftVersion + " 的版本");
        }
        return versions;
    }

    private static String neoForgeVersionPrefix(String minecraftVersion) throws IOException {
        String[] parts = minecraftVersion.split("\\.");
        if (parts.length < 2 || !"1".equals(parts[0])) {
            throw new IOException("无法识别 Minecraft 版本：" + minecraftVersion);
        }
        String gameMinor = parts.length > 2 ? parts[2] : "0";
        return parts[1] + "." + gameMinor + ".";
    }

    private static int compareVersions(String left, String right) {
        String[] leftParts = left.split("[.-]");
        String[] rightParts = right.split("[.-]");
        int count = Math.max(leftParts.length, rightParts.length);
        for (int i = 0; i < count; i++) {
            String a = i < leftParts.length ? leftParts[i] : "0";
            String b = i < rightParts.length ? rightParts[i] : "0";
            int comparison;
            try {
                comparison = Integer.compare(Integer.parseInt(a), Integer.parseInt(b));
            } catch (NumberFormatException exception) {
                comparison = a.compareToIgnoreCase(b);
            }
            if (comparison != 0) {
                return comparison;
            }
        }
        return 0;
    }

    private static List<Path> findInstalledProfiles(Path gameDirectory, String minecraftVersion, LoaderType type)
            throws IOException {
        List<Path> matches = new ArrayList<Path>();
        Path versionsDirectory = gameDirectory.resolve("versions");
        try (java.util.stream.Stream<Path> directories = Files.list(versionsDirectory)) {
            java.util.Iterator<Path> iterator = directories.iterator();
            while (iterator.hasNext()) {
                Path directory = iterator.next();
                if (!Files.isDirectory(directory)) {
                    continue;
                }
                Path jsonPath = directory.resolve(directory.getFileName().toString() + ".json");
                if (!Files.isRegularFile(jsonPath)) {
                    continue;
                }
                JsonObject profile;
                try {
                    profile = parseJson(Files.readAllBytes(jsonPath));
                } catch (IOException | RuntimeException exception) {
                    continue;
                }
                String parent = optionalString(profile, "inheritsFrom");
                String id = optionalString(profile, "id");
                String idLower = id == null ? "" : id.toLowerCase();
                if (minecraftVersion.equals(parent) && matchesType(type, idLower)) {
                    matches.add(directory);
                }
            }
        }
        return matches;
    }

    private static boolean matchesType(LoaderType type, String id) {
        switch (type) {
            case FABRIC:
                return id.contains("fabric");
            case FORGE:
                return id.contains("forge") && !id.contains("neoforge");
            case NEOFORGE:
                return id.contains("neoforge");
            case QUILT:
                return id.contains("quilt");
            default:
                return false;
        }
    }

    private static Path download(String url, Path target) throws IOException {
        if (Files.isRegularFile(target) && Files.size(target) > 0) {
            return target;
        }
        Files.createDirectories(target.getParent());
        Path temporary = target.resolveSibling(target.getFileName() + ".part");
        HttpURLConnection connection = openConnection(url);
        try {
            checkResponse(connection, url);
            try (InputStream input = connection.getInputStream()) {
                Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            Files.deleteIfExists(temporary);
            throw exception;
        } finally {
            connection.disconnect();
        }
        if (Files.size(temporary) < 1024) {
            Files.deleteIfExists(temporary);
            throw new IOException("下载的加载器安装器文件不完整：" + url);
        }
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        return target;
    }

    private static JsonObject parseJson(byte[] bytes) throws IOException {
        try {
            return new JsonParser().parse(new String(bytes, "UTF-8")).getAsJsonObject();
        } catch (RuntimeException exception) {
            throw new IOException("加载器服务返回了无效 JSON", exception);
        }
    }

    private static JsonArray parseJsonArray(byte[] bytes) throws IOException {
        try {
            return new JsonParser().parse(new String(bytes, "UTF-8")).getAsJsonArray();
        } catch (RuntimeException exception) {
            throw new IOException("加载器服务返回了无效 JSON 数组", exception);
        }
    }

    private static byte[] readUrl(String url) throws IOException {
        HttpURLConnection connection = openConnection(url);
        try {
            checkResponse(connection, url);
            try (InputStream input = connection.getInputStream();
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                }
                return output.toByteArray();
            }
        } finally {
            connection.disconnect();
        }
    }

    private static String latestMavenVersion(String url) throws IOException {
        String metadata = new String(readUrl(url), "UTF-8");
        Matcher matcher = Pattern.compile("<(latest|release)>([^<]+)</\\1>").matcher(metadata);
        if (matcher.find()) {
            return matcher.group(2).trim();
        }
        throw new IOException("Maven 元数据中未找到安装器版本：" + url);
    }

    private static HttpURLConnection openConnection(String url) throws IOException {
        URL parsed = new URL(url);
        if (!"https".equalsIgnoreCase(parsed.getProtocol())) {
            throw new IOException("加载器下载地址必须使用 HTTPS：" + url);
        }
        HttpURLConnection connection = (HttpURLConnection) parsed.openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "StarFlux-MC-Launcher/1.0");
        return connection;
    }

    private static void checkResponse(HttpURLConnection connection, String url) throws IOException {
        int status = connection.getResponseCode();
        if (status < 200 || status >= 300) {
            throw new IOException("下载失败，HTTP " + status + "：" + url);
        }
    }

    private static String optionalString(JsonObject object, String key) {
        JsonElement element = object == null ? null : object.get(key);
        return element == null || element.isJsonNull() ? null : element.getAsString();
    }

    private static void requireVersion(String version) throws IOException {
        if (version == null || !version.matches("[A-Za-z0-9._+-]+")) {
            throw new IOException("无效的版本号：" + version);
        }
    }

    private static Path javaExecutable() throws IOException {
        String executable = System.getProperty("os.name").toLowerCase().contains("win")
                ? "java.exe" : "java";
        Path java = new File(new File(System.getProperty("java.home"), "bin"), executable).toPath();
        if (!Files.isRegularFile(java)) {
            throw new IOException("找不到 Java 可执行文件：" + java);
        }
        return java;
    }

    public interface ProgressListener {
        void onProgress(String message);
    }
}
