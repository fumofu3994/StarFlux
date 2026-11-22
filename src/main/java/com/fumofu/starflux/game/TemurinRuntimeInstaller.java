package com.fumofu.starflux.game;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.UUID;
import java.util.EnumSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.nio.file.attribute.PosixFilePermission;

public final class TemurinRuntimeInstaller {
    private TemurinRuntimeInstaller() {
    }

    public static JavaRuntimeManager.JavaInstallation install(int majorVersion,
                                                               JavaRuntimeManager.ProgressListener listener)
            throws IOException {
        if (majorVersion != 8 && majorVersion != 17 && majorVersion != 21 && majorVersion != 25) {
            throw new IOException("不支持安装 Java " + majorVersion);
        }
        Path installRoot = JavaRuntimeManager.managedJavaDirectory();
        Files.createDirectories(installRoot);

        JsonArray releases = parseArray(readUrl("https://api.adoptium.net/v3/assets/latest/" + majorVersion
                + "/hotspot?architecture=" + architecture() + "&archive_type=zip&heap_size=normal"
                + "&image_type=jre&os=" + operatingSystem() + "&vendor=eclipse&page_size=1"));
        if (releases.isEmpty()) {
            throw new IOException("Eclipse Temurin 尚未提供适用于此设备的 Java " + majorVersion);
        }
        JsonObject release = releases.get(0).getAsJsonObject();
        JsonObject binary = release.getAsJsonObject("binary");
        JsonObject archive = binary == null ? null : binary.getAsJsonObject("package");
        String downloadUrl = getString(archive, "link");
        String checksum = getString(archive, "checksum");
        String releaseName = getString(release, "release_name");
        if (downloadUrl == null || !downloadUrl.startsWith("https://")) {
            throw new IOException("Temurin API 返回了无效的 Java 下载地址");
        }

        String folderName = "temurin-" + majorVersion + "-"
                + (releaseName == null ? UUID.randomUUID().toString().substring(0, 8)
                : releaseName.replaceAll("[^A-Za-z0-9._-]", "-"));
        Path target = installRoot.resolve(folderName).normalize();
        if (!target.startsWith(installRoot)) {
            throw new IOException("Java 安装目标目录无效：" + target);
        }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            Path existingExecutable = findJavaExecutable(target);
            if (existingExecutable == null) {
                throw new IOException("Java 安装目录已存在但不完整：" + target);
            }
            JavaRuntimeManager.JavaInstallation existing =
                    JavaRuntimeManager.inspectExecutable(existingExecutable);
            listener.onProgress(1.0, "Java " + majorVersion + " 已安装");
            return existing;
        }

        Path archiveFile = installRoot.resolve(".download-" + UUID.randomUUID() + ".zip");
        Path staging = installRoot.resolve(".staging-" + UUID.randomUUID()).normalize();
        try {
            download(downloadUrl, archiveFile, listener);
            if (checksum != null && !checksum.isBlank() && !checksum.equalsIgnoreCase(sha256(archiveFile))) {
                throw new IOException("Java 安装包 SHA-256 校验失败");
            }
            Files.createDirectories(staging);
            extract(archiveFile, staging, listener);
            Path executable = findJavaExecutable(staging);
            if (executable == null) {
                throw new IOException("Java 安装包中找不到 java 可执行文件");
            }
            makeExecutable(executable);
            try (var paths = Files.walk(staging, 8)) {
                for (Path path : paths.filter(candidate -> candidate.getFileName() != null
                        && candidate.getFileName().toString().equals("jspawnhelper")).toList()) {
                    makeExecutable(path);
                }
            }
            Path relativeExecutable = staging.relativize(executable);
            moveDirectory(staging, target);
            JavaRuntimeManager.JavaInstallation installation;
            try {
                installation = JavaRuntimeManager.inspectExecutable(target.resolve(relativeExecutable));
            } catch (IOException exception) {
                deleteTree(target);
                throw exception;
            }
            listener.onProgress(1.0, "Java " + majorVersion + " 安装完成");
            return installation;
        } finally {
            Files.deleteIfExists(archiveFile);
            deleteTree(staging);
        }
    }

    private static void download(String url, Path target, JavaRuntimeManager.ProgressListener listener)
            throws IOException {
        HttpURLConnection connection = openConnection(url);
        try {
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300 || !"https".equalsIgnoreCase(connection.getURL().getProtocol())) {
                throw new IOException("下载 Java 失败，HTTP " + status);
            }
            long total = connection.getContentLengthLong();
            long completed = 0;
            long nextReport = Math.max(1, total / 100);
            try (var input = new BufferedInputStream(connection.getInputStream());
                 OutputStream output = new BufferedOutputStream(Files.newOutputStream(target))) {
                byte[] buffer = new byte[65536];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                    completed += read;
                    if (total <= 0 || completed >= nextReport) {
                        double progress = total > 0 ? Math.min(0.75, (double) completed / total * 0.75) : -1;
                        listener.onProgress(progress, "正在下载 Java " + formatBytes(completed)
                                + (total > 0 ? " / " + formatBytes(total) : ""));
                        nextReport = completed + Math.max(1, total / 100);
                    }
                }
            }
        } finally {
            connection.disconnect();
        }
    }

    private static void extract(Path archive, Path destination, JavaRuntimeManager.ProgressListener listener)
            throws IOException {
        long expandedBytes = 0;
        int entries = 0;
        try (ZipInputStream input = new ZipInputStream(new BufferedInputStream(Files.newInputStream(archive)))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                if (++entries > 100_000) {
                    throw new IOException("Java 安装包包含过多文件");
                }
                Path outputPath = destination.resolve(entry.getName()).normalize();
                if (!outputPath.startsWith(destination)) {
                    throw new IOException("Java 安装包包含无效路径");
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(outputPath);
                } else {
                    Files.createDirectories(outputPath.getParent());
                    try (OutputStream output = new BufferedOutputStream(Files.newOutputStream(outputPath))) {
                        byte[] buffer = new byte[65536];
                        int read;
                        while ((read = input.read(buffer)) != -1) {
                            expandedBytes += read;
                            if (expandedBytes > 2L * 1024 * 1024 * 1024) {
                                throw new IOException("Java 安装包解压后超过 2 GB");
                            }
                            output.write(buffer, 0, read);
                        }
                    }
                }
                input.closeEntry();
                listener.onProgress(0.75, "正在解压 Java 安装包...");
            }
        }
    }

    private static Path findJavaExecutable(Path root) throws IOException {
        String executableName = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")
                ? "java.exe" : "java";
        try (var paths = Files.walk(root, 8)) {
            return paths.filter(path -> path.getFileName() != null
                            && path.getFileName().toString().equalsIgnoreCase(executableName)
                            && Files.isRegularFile(path))
                    .findFirst().orElse(null);
        }
    }

    private static JsonArray parseArray(byte[] json) throws IOException {
        try {
            return new JsonParser().parse(new String(json, StandardCharsets.UTF_8)).getAsJsonArray();
        } catch (RuntimeException exception) {
            throw new IOException("Temurin API 返回了无效 JSON", exception);
        }
    }

    private static byte[] readUrl(String url) throws IOException {
        HttpURLConnection connection = openConnection(url);
        try {
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300 || !"https".equalsIgnoreCase(connection.getURL().getProtocol())) {
                throw new IOException("请求 Temurin API 失败，HTTP " + status);
            }
            try (var input = connection.getInputStream();
                 var output = new java.io.ByteArrayOutputStream()) {
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

    private static HttpURLConnection openConnection(String url) throws IOException {
        URL parsed = new URL(url);
        if (!"https".equalsIgnoreCase(parsed.getProtocol())) {
            throw new IOException("Java 下载地址必须使用 HTTPS");
        }
        HttpURLConnection connection = (HttpURLConnection) parsed.openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(60000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "StarFlux-MC-Launcher/1.0");
        return connection;
    }

    private static String getString(JsonObject object, String key) {
        return object == null || !object.has(key) || object.get(key).isJsonNull()
                ? null : object.get(key).getAsString();
    }

    private static String sha256(Path file) throws IOException {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 Java 不支持 SHA-256", exception);
        }
        try (var input = Files.newInputStream(file)) {
            byte[] buffer = new byte[65536];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        StringBuilder result = new StringBuilder();
        for (byte value : digest.digest()) {
            result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        return result.toString();
    }

    private static String operatingSystem() throws IOException {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return "windows";
        }
        if (os.contains("mac")) {
            return "mac";
        }
        if (os.contains("linux")) {
            return "linux";
        }
        throw new IOException("Temurin 尚不支持当前操作系统：" + os);
    }

    private static String architecture() throws IOException {
        String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        if (arch.equals("amd64") || arch.equals("x86_64")) {
            return "x64";
        }
        if (arch.equals("aarch64") || arch.equals("arm64")) {
            return "aarch64";
        }
        throw new IOException("Temurin 尚不支持当前处理器架构：" + arch);
    }

    private static String formatBytes(long bytes) {
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024));
    }

    private static void moveDirectory(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target);
        }
    }

    private static void makeExecutable(Path file) throws IOException {
        try {
            Files.setPosixFilePermissions(file, EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_READ,
                    PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_READ,
                    PosixFilePermission.OTHERS_EXECUTE));
        } catch (UnsupportedOperationException ignored) {
            if (!file.toFile().setExecutable(true)) {
                throw new IOException("无法设置 Java 可执行权限：" + file);
            }
        }
    }

    private static void deleteTree(Path directory) throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
