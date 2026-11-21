package com.fumofu.starflux;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

public class MinecraftInstaller {
    private static final String VERSION_MANIFEST =
            "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    private static final int CONNECT_TIMEOUT_MILLIS = 15000;
    private static final int READ_TIMEOUT_MILLIS = 30000;
    private final MirrorSource mirrorSource;

    public MinecraftInstaller(MirrorSource mirrorSource) {
        this.mirrorSource = mirrorSource;
    }

    public List<VersionEntry> fetchVersions() throws IOException {
        JsonObject manifest = parseJson(readUrl(VERSION_MANIFEST, mirrorSource));
        JsonArray versions = manifest.getAsJsonArray("versions");
        List<VersionEntry> result = new ArrayList<VersionEntry>();
        for (JsonElement element : versions) {
            JsonObject version = element.getAsJsonObject();
            result.add(new VersionEntry(
                    requiredString(version, "id"),
                    requiredString(version, "type"),
                    requiredString(version, "url"),
                    optionalString(version, "sha1")));
        }
        return result;
    }

    public void install(VersionEntry version, Path gameDirectory, ProgressListener listener) throws Exception {
        if (!version.getId().matches("[A-Za-z0-9._-]+")) {
            throw new IOException("无效的 Minecraft 版本号：" + version.getId());
        }

        JsonObject versionJson = parseJson(readAndVerify(version.getUrl(), version.getSha1(), mirrorSource));
        Path versionDirectory = gameDirectory.resolve("versions").resolve(version.getId());
        Files.createDirectories(versionDirectory);
        writeBytes(versionDirectory.resolve(version.getId() + ".json"), versionJson.toString().getBytes("UTF-8"));

        Map<Path, DownloadItem> downloads = new LinkedHashMap<Path, DownloadItem>();
        JsonObject client = versionJson.getAsJsonObject("downloads").getAsJsonObject("client");
        addDownload(downloads, client, versionDirectory.resolve(version.getId() + ".jar"));

        JsonArray libraries = versionJson.getAsJsonArray("libraries");
        if (libraries != null) {
            for (JsonElement element : libraries) {
                JsonObject library = element.getAsJsonObject();
                if (!isLibraryAllowed(library)) {
                    continue;
                }

                JsonObject libraryDownloads = library.getAsJsonObject("downloads");
                if (libraryDownloads == null) {
                    addLegacyLibrary(downloads, library, gameDirectory, null);
                    continue;
                }
                JsonObject artifact = libraryDownloads.getAsJsonObject("artifact");
                if (artifact != null) {
                    String relativePath = requiredString(artifact, "path");
                    addDownload(downloads, artifact, resolveInside(gameDirectory.resolve("libraries"), relativePath));
                } else {
                    addLegacyLibrary(downloads, library, gameDirectory, null);
                }

                JsonObject natives = library.getAsJsonObject("natives");
                String nativeKey = natives == null ? null : String.valueOf(natives.get(osName()));
                if (nativeKey != null) {
                    nativeKey = nativeKey.replace("${arch}", nativeArchitecture());
                    JsonObject classifiers = libraryDownloads.getAsJsonObject("classifiers");
                    JsonObject nativeArtifact = classifiers == null ? null : classifiers.getAsJsonObject(nativeKey);
                    if (nativeArtifact != null) {
                        String relativePath = requiredString(nativeArtifact, "path");
                        addDownload(downloads, nativeArtifact,
                                resolveInside(gameDirectory.resolve("libraries"), relativePath));
                    } else {
                        addLegacyLibrary(downloads, library, gameDirectory, nativeKey);
                    }
                }
            }
        }

        JsonObject assetIndex = versionJson.getAsJsonObject("assetIndex");
        if (assetIndex == null) {
            throw new IOException("版本信息缺少资源索引");
        }
        String assetIndexId = requiredString(assetIndex, "id");
        byte[] assetIndexBytes = readAndVerify(requiredString(assetIndex, "url"),
                optionalString(assetIndex, "sha1"), mirrorSource);
        Path assetIndexPath = gameDirectory.resolve("assets").resolve("indexes").resolve(assetIndexId + ".json");
        writeBytes(assetIndexPath, assetIndexBytes);

        JsonObject assets = parseJson(assetIndexBytes);
        JsonObject objects = assets.getAsJsonObject("objects");
        if (objects == null) {
            throw new IOException("资源索引格式无效");
        }
        for (Map.Entry<String, JsonElement> entry : objects.entrySet()) {
            JsonObject object = entry.getValue().getAsJsonObject();
            String hash = requiredString(object, "hash");
            if (!hash.matches("[a-fA-F0-9]{40}")) {
                throw new IOException("资源索引中包含无效 SHA-1：" + entry.getKey());
            }
            Path objectPath = gameDirectory.resolve("assets").resolve("objects")
                    .resolve(hash.substring(0, 2)).resolve(hash);
            addItem(downloads, new DownloadItem(
                    "https://resources.download.minecraft.net/" + hash.substring(0, 2) + "/" + hash,
                    objectPath,
                    hash));
        }

        downloadAll(new ArrayList<DownloadItem>(downloads.values()), listener, mirrorSource);
    }

    private static void addLegacyLibrary(Map<Path, DownloadItem> downloads, JsonObject library, Path gameDirectory,
                                         String classifier) throws IOException {
        String coordinate = requiredString(library, "name");
        String extension = "jar";
        int extensionSeparator = coordinate.indexOf('@');
        if (extensionSeparator >= 0) {
            extension = coordinate.substring(extensionSeparator + 1);
            coordinate = coordinate.substring(0, extensionSeparator);
        }
        String[] parts = coordinate.split(":");
        if (parts.length < 3 || parts.length > 4) {
            throw new IOException("无效的 Maven 依赖坐标：" + coordinate);
        }
        String groupPath = parts[0].replace('.', '/');
        String artifactId = parts[1];
        String version = parts[2];
        String artifactClassifier = classifier;
        if (artifactClassifier == null && parts.length == 4) {
            artifactClassifier = parts[3];
        }
        String fileName = artifactId + "-" + version
                + (artifactClassifier == null ? "" : "-" + artifactClassifier) + "." + extension;
        String relativePath = groupPath + "/" + artifactId + "/" + version + "/" + fileName;
        Path target = resolveInside(gameDirectory.resolve("libraries"), relativePath);

        String baseUrl = optionalString(library, "url");
        if (baseUrl == null || baseUrl.trim().isEmpty()) {
            baseUrl = "https://libraries.minecraft.net/";
        }
        if (!baseUrl.endsWith("/")) {
            baseUrl += "/";
        }
        String url = new URL(new URL(baseUrl), relativePath).toExternalForm();
        JsonArray checksums = library.getAsJsonArray("checksums");
        String checksum = checksums == null || checksums.size() == 0 ? null : checksums.get(0).getAsString();
        ensureHttpUrl(url);
        addItem(downloads, new DownloadItem(url, target, checksum));
    }

    private void downloadAll(List<DownloadItem> downloads, ProgressListener listener, MirrorSource mirrorSource)
            throws Exception {
        int threadCount = Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors()));
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        final AtomicInteger completed = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<Future<?>>();
        try {
            for (final DownloadItem item : downloads) {
                futures.add(executor.submit(() -> {
                    download(item, mirrorSource);
                    int count = completed.incrementAndGet();
                    listener.onProgress(count, downloads.size(), item.path.getFileName().toString());
                    return null;
                }));
            }
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (ExecutionException exception) {
                    Throwable cause = exception.getCause();
                    if (cause instanceof Exception) {
                        throw (Exception) cause;
                    }
                    if (cause instanceof Error) {
                        throw (Error) cause;
                    }
                    throw new IOException("下载任务失败", cause);
                }
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private void download(DownloadItem item, MirrorSource mirrorSource) throws IOException {
        if (Files.isRegularFile(item.path) && (item.sha1 == null || item.sha1.equalsIgnoreCase(sha1(item.path)))) {
            return;
        }

        Files.createDirectories(item.path.getParent());
        Path temporary = item.path.resolveSibling(item.path.getFileName().toString() + ".part");
        HttpURLConnection connection = openConnection(item.url, mirrorSource);
        try {
            checkResponse(connection, item.url);
            MessageDigest digest = newSha1();
            try (InputStream input = connection.getInputStream();
                 OutputStream output = Files.newOutputStream(temporary)) {
                byte[] buffer = new byte[65536];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                    digest.update(buffer, 0, read);
                }
            }

            if (item.sha1 != null && !toHex(digest.digest()).equalsIgnoreCase(item.sha1)) {
                Files.deleteIfExists(temporary);
                throw new IOException("文件校验失败：" + item.path.getFileName());
            }
            try {
                Files.move(temporary, item.path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, item.path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            connection.disconnect();
        }
    }

    private boolean isLibraryAllowed(JsonObject library) throws IOException {
        JsonArray rules = library.getAsJsonArray("rules");
        if (rules == null) {
            return true;
        }

        boolean allowed = false;
        for (JsonElement element : rules) {
            JsonObject rule = element.getAsJsonObject();
            if (!ruleMatches(rule)) {
                continue;
            }
            allowed = "allow".equals(requiredString(rule, "action"));
        }
        return allowed;
    }

    private boolean ruleMatches(JsonObject rule) throws IOException {
        JsonObject os = rule.getAsJsonObject("os");
        if (os != null) {
            String expectedName = optionalString(os, "name");
            if (expectedName != null && !expectedName.equals(osName())) {
                return false;
            }
            String expectedArch = optionalString(os, "arch");
            if (expectedArch != null && !Pattern.matches(expectedArch, System.getProperty("os.arch"))) {
                return false;
            }
            String expectedVersion = optionalString(os, "version");
            if (expectedVersion != null && !Pattern.matches(expectedVersion, System.getProperty("os.version"))) {
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

    private static void addDownload(Map<Path, DownloadItem> downloads, JsonObject artifact, Path path)
            throws IOException {
        String url = requiredString(artifact, "url");
        ensureHttpUrl(url);
        addItem(downloads, new DownloadItem(url, path, optionalString(artifact, "sha1")));
    }

    private static void addItem(Map<Path, DownloadItem> downloads, DownloadItem item) {
        downloads.putIfAbsent(item.path.normalize(), item);
    }

    private static Path resolveInside(Path base, String relativePath) throws IOException {
        Path resolved = base.resolve(relativePath).normalize();
        if (!resolved.startsWith(base.normalize())) {
            throw new IOException("下载路径超出游戏目录：" + relativePath);
        }
        return resolved;
    }

    private static byte[] readAndVerify(String url, String expectedSha1, MirrorSource mirrorSource)
            throws IOException {
        byte[] bytes = readUrl(url, mirrorSource);
        if (expectedSha1 != null && !expectedSha1.equalsIgnoreCase(sha1(bytes))) {
            throw new IOException("下载文件校验失败：" + url);
        }
        return bytes;
    }

    private static byte[] readUrl(String url, MirrorSource mirrorSource) throws IOException {
        HttpURLConnection connection = openConnection(url, mirrorSource);
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

    private static HttpURLConnection openConnection(String url, MirrorSource mirrorSource) throws IOException {
        String resolvedUrl = mirrorSource.resolveUrl(url);
        ensureHttpUrl(resolvedUrl);
        HttpURLConnection connection = (HttpURLConnection) new URL(resolvedUrl).openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
        connection.setReadTimeout(READ_TIMEOUT_MILLIS);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "StarFlux-MC-Launcher/1.0");
        return connection;
    }

    private static void ensureHttpUrl(String value) throws IOException {
        URL url = new URL(value);
        if (!"https".equalsIgnoreCase(url.getProtocol()) && !"http".equalsIgnoreCase(url.getProtocol())) {
            throw new IOException("不支持的下载协议：" + value);
        }
    }

    private static void checkResponse(HttpURLConnection connection, String url) throws IOException {
        int status = connection.getResponseCode();
        if (status < 200 || status >= 300) {
            throw new IOException("下载失败，HTTP " + status + "：" + url);
        }
    }

    private static JsonObject parseJson(byte[] bytes) throws IOException {
        try {
            return new JsonParser().parse(new String(bytes, "UTF-8")).getAsJsonObject();
        } catch (RuntimeException exception) {
            throw new IOException("Minecraft 返回了无效的 JSON 数据", exception);
        }
    }

    private static String requiredString(JsonObject object, String key) throws IOException {
        String value = optionalString(object, key);
        if (value == null || value.trim().isEmpty()) {
            throw new IOException("Minecraft 元数据缺少字段：" + key);
        }
        return value;
    }

    private static String optionalString(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element == null || element.isJsonNull() ? null : element.getAsString();
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

    private static void writeBytes(Path path, byte[] contents) throws IOException {
        Files.createDirectories(path.getParent());
        Files.write(path, contents);
    }

    private static String sha1(Path path) throws IOException {
        MessageDigest digest = newSha1();
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[65536];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return toHex(digest.digest());
    }

    private static String sha1(byte[] bytes) {
        return toHex(newSha1().digest(bytes));
    }

    private static MessageDigest newSha1() {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 Java 环境不支持 SHA-1", exception);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        return result.toString();
    }

    public interface ProgressListener {
        void onProgress(int completed, int total, String fileName);
    }

    public static final class VersionEntry {
        private final String id;
        private final String type;
        private final String url;
        private final String sha1;

        public VersionEntry(String id, String type, String url, String sha1) {
            this.id = id;
            this.type = type;
            this.url = url;
            this.sha1 = sha1;
        }

        public String getId() {
            return id;
        }

        public String getType() {
            return type;
        }

        public String getUrl() {
            return url;
        }

        public String getSha1() {
            return sha1;
        }

        @Override
        public String toString() {
            return id + "  (" + type + ")";
        }
    }

    private static final class DownloadItem {
        private final String url;
        private final Path path;
        private final String sha1;

        private DownloadItem(String url, Path path, String sha1) {
            this.url = url;
            this.path = path;
            this.sha1 = sha1;
        }
    }
}
