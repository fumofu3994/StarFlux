package com.fumofu.starflux.multiplayer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;

public final class TerracottaService implements AutoCloseable {
    private static final String RELEASE_API =
            "https://api.github.com/repos/burningtnt/Terracotta/releases/latest";
    private static final String PROJECT_URL = "https://github.com/burningtnt/Terracotta";
    private static final long MAX_ARCHIVE_SIZE = 100L * 1024L * 1024L;

    private final Path dataDirectory;
    private final Path terracottaDirectory;
    private final Path portFile;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "terracotta-service");
        thread.setDaemon(true);
        return thread;
    });
    private final ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "terracotta-state-poller");
        thread.setDaemon(true);
        return thread;
    });
    private volatile Consumer<RoomState> stateListener = state -> { };
    private volatile BiConsumer<Double, String> progressListener = (progress, message) -> { };
    private volatile int apiPort = -1;
    private volatile Process process;
    private volatile boolean closed;

    public TerracottaService() {
        String configuredHome = System.getProperty("starflux.home");
        dataDirectory = configuredHome == null || configuredHome.trim().isEmpty()
                ? Paths.get(System.getProperty("user.home"), ".starflux").toAbsolutePath().normalize()
                : Paths.get(configuredHome).toAbsolutePath().normalize();
        terracottaDirectory = dataDirectory.resolve("terracotta");
        portFile = terracottaDirectory.resolve("api-port.json");
    }

    public void setStateListener(Consumer<RoomState> listener) {
        stateListener = listener == null ? state -> { } : listener;
    }

    public void setProgressListener(BiConsumer<Double, String> listener) {
        progressListener = listener == null ? (progress, message) -> { } : listener;
    }

    public void startPolling() {
        poller.scheduleWithFixedDelay(this::pollState, 0, 500, TimeUnit.MILLISECONDS);
    }

    public CompletableFuture<Void> createRoom(String playerName) {
        return CompletableFuture.runAsync(() -> {
            try {
                ensureRunning();
                ensureWaiting();
                request("/state/scanning?player=" + encode(playerName));
            } catch (IOException exception) {
                throw new java.util.concurrent.CompletionException(exception);
            }
        }, worker);
    }

    public CompletableFuture<Void> joinRoom(String roomCode, String playerName) {
        return CompletableFuture.runAsync(() -> {
            try {
                ensureRunning();
                ensureWaiting();
                request("/state/guesting?room=" + encode(roomCode) + "&player=" + encode(playerName));
            } catch (IOException exception) {
                throw new java.util.concurrent.CompletionException(exception);
            }
        }, worker);
    }

    public CompletableFuture<Void> returnToWaiting() {
        return CompletableFuture.runAsync(() -> {
            try {
                if (apiPort > 0) {
                    request("/state/ide");
                }
            } catch (IOException exception) {
                throw new java.util.concurrent.CompletionException(exception);
            }
        }, worker);
    }

    @Override
    public void close() {
        closed = true;
        poller.shutdownNow();
        worker.shutdown();
    }

    private void ensureWaiting() throws IOException {
        JsonObject state = request("/state");
        String currentState = state.has("state") ? state.get("state").getAsString() : "";
        if (!"waiting".equals(currentState)) {
            throw new IOException("陶瓦联机当前状态为 " + (currentState.isEmpty() ? "未知" : currentState)
                    + "。请先断开现有房间，再创建或加入房间。");
        }
    }

    private void ensureRunning() throws IOException {
        if (apiPort > 0) {
            return;
        }
        reportProgress(-1.0, "正在检查陶瓦联机核心...");
        Files.createDirectories(terracottaDirectory);
        Path executable = installLatestRelease();
        reportProgress(-1.0, "正在启动陶瓦联机核心...");
        Files.deleteIfExists(portFile);
        Files.deleteIfExists(portFile.resolveSibling(portFile.getFileName() + ".tmp"));

        Path logFile = dataDirectory.resolve("logs").resolve("terracotta.log");
        Files.createDirectories(logFile.getParent());
        ProcessBuilder builder = new ProcessBuilder(executable.toString(), "--hmcl", portFile.toString());
        builder.directory(terracottaDirectory.toFile());
        builder.redirectErrorStream(true);
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
        process = builder.start();

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            if (Files.isRegularFile(portFile)) {
                JsonObject portData = readJson(portFile);
                if (portData.has("port")) {
                    int discoveredPort = portData.get("port").getAsInt();
                    if (discoveredPort > 0 && discoveredPort <= 65535) {
                        apiPort = discoveredPort;
                        request("/meta");
                        pollState();
                        reportProgress(1.0, "陶瓦联机核心已就绪");
                        return;
                    }
                }
            }
            Process current = process;
            if (current != null && !current.isAlive() && !Files.isRegularFile(portFile)) {
                throw new IOException("陶瓦联机进程提前退出。请查看日志：" + logFile);
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("等待陶瓦联机启动时被中断", exception);
            }
        }
        throw new IOException("等待陶瓦联机服务超时。请查看日志：" + logFile);
    }

    private Path installLatestRelease() throws IOException {
        String os = operatingSystem();
        String arch = architecture();
        JsonObject release = readJson(RELEASE_API);
        String tag = requiredString(release, "tag_name");
        String version = tag.startsWith("v") ? tag.substring(1) : tag;
        String suffix = "-" + os + "-" + arch + "-pkg.tar.gz";
        JsonArray assets = release.getAsJsonArray("assets");
        JsonObject asset = null;
        for (JsonElement element : assets) {
            JsonObject candidate = element.getAsJsonObject();
            if (candidate.get("name").getAsString().endsWith(suffix)) {
                asset = candidate;
                break;
            }
        }
        if (asset == null) {
            throw new IOException("陶瓦联机没有提供当前平台的发行包（" + os + " " + arch + "）。"
                    + "可前往 " + PROJECT_URL + " 手动下载。");
        }

        String assetName = requiredString(asset, "name");
        String assetUrl = requiredString(asset, "browser_download_url");
        long assetSize = asset.has("size") ? asset.get("size").getAsLong() : -1;
        if (assetSize > MAX_ARCHIVE_SIZE) {
            throw new IOException("陶瓦联机发行包超过安全下载上限");
        }
        Path executable = terracottaDirectory.resolve(executableName());
        Path versionFile = terracottaDirectory.resolve("version.txt");
        String installedVersion = Files.isRegularFile(versionFile)
                ? Files.readString(versionFile, StandardCharsets.UTF_8).trim() : "";
        if (Files.isRegularFile(executable) && version.equals(installedVersion)) {
            return executable;
        }

        Path archive = terracottaDirectory.resolve(assetName + ".part");
        Files.deleteIfExists(archive);
        reportProgress(0.0, "正在下载陶瓦联机核心...");
        download(assetUrl, archive, assetSize);
        reportProgress(-1.0, "正在校验陶瓦联机核心...");
        verifyDigest(asset, archive);

        Path stagedExecutable = terracottaDirectory.resolve(executableName() + ".part");
        Path stagedRuntime = isWindows() ? terracottaDirectory.resolve("VCRUNTIME140.DLL.part") : null;
        Files.deleteIfExists(stagedExecutable);
        if (stagedRuntime != null) {
            Files.deleteIfExists(stagedRuntime);
        }
        try {
            reportProgress(-1.0, "正在解压陶瓦联机核心...");
            extractPackage(archive, stagedExecutable, stagedRuntime, version, os, arch);
            if (!Files.isRegularFile(stagedExecutable)) {
                throw new IOException("陶瓦联机发行包中没有找到可执行文件");
            }
            moveReplacing(stagedExecutable, executable);
            if (stagedRuntime != null) {
                Path runtime = terracottaDirectory.resolve("VCRUNTIME140.DLL");
                if (!Files.isRegularFile(stagedRuntime)) {
                    throw new IOException("陶瓦联机发行包缺少 VCRUNTIME140.DLL");
                }
                moveReplacing(stagedRuntime, runtime);
            }
            Files.writeString(versionFile, version, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } finally {
            Files.deleteIfExists(archive);
            Files.deleteIfExists(stagedExecutable);
            if (stagedRuntime != null) {
                Files.deleteIfExists(stagedRuntime);
            }
        }
        if (!isWindows()) {
            executable.toFile().setExecutable(true, true);
        }
        return executable;
    }

    private void extractPackage(Path archive, Path executableTarget, Path runtimeTarget,
                                String version, String os, String arch) throws IOException {
        String executableEntry = "terracotta-" + version + "-" + os + "-" + arch + (isWindows() ? ".exe" : "");
        try (InputStream input = new BufferedInputStream(new GZIPInputStream(Files.newInputStream(archive)))) {
            byte[] header = new byte[512];
            while (readBlock(input, header)) {
                if (isZeroBlock(header)) {
                    return;
                }
                String name = tarString(header, 0, 100);
                long size = parseOctal(header, 124, 12);
                int type = header[156] & 0xff;
                if (size < 0 || size > MAX_ARCHIVE_SIZE) {
                    throw new IOException("陶瓦联机发行包包含无效文件长度");
                }

                Path target = null;
                if (type == 0 || type == '0') {
                    if (name.equals(executableEntry)) {
                        target = executableTarget;
                    } else if (runtimeTarget != null && name.equals("VCRUNTIME140.DLL")) {
                        target = runtimeTarget;
                    }
                }
                if (target == null) {
                    skipFully(input, size);
                } else {
                    try (OutputStream output = new BufferedOutputStream(Files.newOutputStream(target))) {
                        copyExactly(input, output, size);
                    }
                }
                long padding = (512 - size % 512) % 512;
                skipFully(input, padding);
            }
        }
    }

    private void pollState() {
        if (closed || apiPort <= 0) {
            return;
        }
        try {
            JsonObject state = request("/state");
            stateListener.accept(new RoomState(state));
        } catch (IOException | RuntimeException exception) {
            stateListener.accept(RoomState.failure(exception.getMessage()));
        }
    }

    private JsonObject request(String path) throws IOException {
        if (apiPort <= 0) {
            throw new IOException("陶瓦联机服务尚未启动");
        }
        HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:" + apiPort + path)
                .openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(2000);
        connection.setReadTimeout(3000);
        connection.setRequestProperty("Accept", "application/json");
        try {
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IOException("陶瓦联机接口返回 HTTP " + status + "：" + path);
            }
            try (InputStream input = connection.getInputStream()) {
                byte[] response = input.readNBytes(1024 * 1024);
                if (response.length == 0) {
                    return new JsonObject();
                }
                try {
                    return new JsonParser().parse(new String(response, StandardCharsets.UTF_8)).getAsJsonObject();
                } catch (RuntimeException exception) {
                    throw new IOException("陶瓦联机接口返回了无效 JSON：" + path, exception);
                }
            }
        } finally {
            connection.disconnect();
        }
    }

    private static JsonObject readJson(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            return new JsonParser().parse(new String(input.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (RuntimeException exception) {
            throw new IOException("陶瓦联机返回了无效 JSON", exception);
        }
    }

    private static JsonObject readJson(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(20000);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("User-Agent", "StarFlux-MC-Launcher");
        try {
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IOException("读取陶瓦联机发行信息失败（HTTP " + status + "）");
            }
            try (InputStream input = connection.getInputStream()) {
                return new JsonParser().parse(new String(input.readAllBytes(), StandardCharsets.UTF_8))
                        .getAsJsonObject();
            } catch (RuntimeException exception) {
                throw new IOException("陶瓦联机发行信息格式无效", exception);
            }
        } finally {
            connection.disconnect();
        }
    }

    private void download(String url, Path target, long expectedSize) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(60000);
        connection.setRequestProperty("User-Agent", "StarFlux-MC-Launcher");
        try {
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IOException("下载陶瓦联机失败（HTTP " + status + "）");
            }
            long contentLength = connection.getContentLengthLong();
            long progressLength = expectedSize > 0 ? expectedSize : contentLength;
            if (contentLength > MAX_ARCHIVE_SIZE || (expectedSize >= 0 && contentLength > 0
                    && contentLength != expectedSize)) {
                throw new IOException("陶瓦联机发行包大小与发行信息不符");
            }
            if (progressLength <= 0) {
                reportProgress(-1.0, "正在下载陶瓦联机核心...");
            }
            try (InputStream input = connection.getInputStream();
                 OutputStream output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
                byte[] buffer = new byte[8192];
                long total = 0;
                long lastUpdate = 0;
                int count;
                while ((count = input.read(buffer)) != -1) {
                    total += count;
                    if (total > MAX_ARCHIVE_SIZE || (expectedSize >= 0 && total > expectedSize)) {
                        throw new IOException("陶瓦联机发行包超过预期大小");
                    }
                    output.write(buffer, 0, count);
                    if (progressLength > 0 && (System.nanoTime() - lastUpdate > TimeUnit.MILLISECONDS.toNanos(100)
                            || total == progressLength)) {
                        lastUpdate = System.nanoTime();
                        reportProgress(Math.min(1.0, (double) total / progressLength),
                                String.format(Locale.ROOT, "正在下载陶瓦联机核心：%.0f%%",
                                        100.0 * total / progressLength));
                    }
                }
                if (expectedSize >= 0 && total != expectedSize) {
                    throw new IOException("陶瓦联机发行包下载不完整");
                }
            }

        } catch (IOException exception) {
            Files.deleteIfExists(target);
            throw exception;
        } finally {
            connection.disconnect();
        }
    }

    private void reportProgress(double progress, String message) {
        progressListener.accept(progress, message);
    }

    private static void verifyDigest(JsonObject asset, Path archive) throws IOException {
        String digest = asset.has("digest") && !asset.get("digest").isJsonNull()
                ? asset.get("digest").getAsString() : "";
        if (digest.isEmpty()) {
            throw new IOException("陶瓦联机发行信息未提供 SHA-256 校验值");
        }
        if (!digest.startsWith("sha256:")) {
            throw new IOException("陶瓦联机发行包使用了不支持的校验算法");
        }
        try (InputStream input = Files.newInputStream(archive)) {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                sha256.update(buffer, 0, count);
            }
            StringBuilder actual = new StringBuilder();
            for (byte value : sha256.digest()) {
                actual.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            }
            if (!actual.toString().equalsIgnoreCase(digest.substring("sha256:".length()))) {
                throw new IOException("陶瓦联机发行包 SHA-256 校验失败");
            }
        } catch (NoSuchAlgorithmException exception) {
            throw new IOException("当前 Java 不支持 SHA-256", exception);
        }
    }

    private static void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String operatingSystem() throws IOException {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return "windows";
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return "macos";
        }
        if (os.contains("linux")) {
            return "linux";
        }
        throw new IOException("陶瓦联机不支持当前操作系统：" + System.getProperty("os.name"));
    }

    private static String architecture() throws IOException {
        String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        if (arch.equals("amd64") || arch.equals("x86_64")) {
            return "x86_64";
        }
        if (arch.equals("aarch64") || arch.equals("arm64")) {
            return "arm64";
        }
        throw new IOException("陶瓦联机不支持当前处理器架构：" + System.getProperty("os.arch"));
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    }

    private static String executableName() {
        return isWindows() ? "terracotta.exe" : "terracotta";
    }

    private static String requiredString(JsonObject json, String key) throws IOException {
        if (!json.has(key) || json.get(key).isJsonNull()) {
            throw new IOException("陶瓦联机发行信息缺少字段：" + key);
        }
        return json.get(key).getAsString();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static boolean readBlock(InputStream input, byte[] block) throws IOException {
        int offset = 0;
        while (offset < block.length) {
            int count = input.read(block, offset, block.length - offset);
            if (count < 0) {
                if (offset == 0) {
                    return false;
                }
                throw new IOException("陶瓦联机发行包 TAR 数据不完整");
            }
            offset += count;
        }
        return true;
    }

    private static boolean isZeroBlock(byte[] block) {
        for (byte value : block) {
            if (value != 0) {
                return false;
            }
        }
        return true;
    }

    private static String tarString(byte[] header, int offset, int length) {
        int end = offset;
        while (end < offset + length && header[end] != 0) {
            end++;
        }
        return new String(header, offset, end - offset, StandardCharsets.UTF_8);
    }

    private static long parseOctal(byte[] header, int offset, int length) throws IOException {
        String value = tarString(header, offset, length).trim();
        if (value.isEmpty()) {
            return 0;
        }
        try {
            return Long.parseLong(value, 8);
        } catch (NumberFormatException exception) {
            throw new IOException("陶瓦联机发行包 TAR 文件长度无效", exception);
        }
    }

    private static void skipFully(InputStream input, long bytes) throws IOException {
        long skipped = 0;
        while (skipped < bytes) {
            long count = input.skip(bytes - skipped);
            if (count <= 0) {
                if (input.read() == -1) {
                    throw new IOException("陶瓦联机发行包 TAR 数据不完整");
                }
                count = 1;
            }
            skipped += count;
        }
    }

    private static void copyExactly(InputStream input, OutputStream output, long bytes) throws IOException {
        byte[] buffer = new byte[8192];
        long remaining = bytes;
        while (remaining > 0) {
            int count = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (count < 0) {
                throw new IOException("陶瓦联机可执行文件内容不完整");
            }
            output.write(buffer, 0, count);
            remaining -= count;
        }
    }

    public static final class RoomState {
        public final String state;
        public final String room;
        public final String url;
        public final String message;
        public final List<String> profiles;

        private RoomState(JsonObject json) {
            state = json.has("state") ? json.get("state").getAsString() : "waiting";
            room = json.has("room") && !json.get("room").isJsonNull() ? json.get("room").getAsString() : "";
            url = json.has("url") && !json.get("url").isJsonNull() ? json.get("url").getAsString() : "";
            message = "exception".equals(state) && json.has("type")
                    ? exceptionMessage(json.get("type").getAsInt()) : "";
            profiles = new ArrayList<String>();
            if (json.has("profiles") && json.get("profiles").isJsonArray()) {
                for (JsonElement element : json.getAsJsonArray("profiles")) {
                    JsonObject profile = element.getAsJsonObject();
                    String name = profile.has("name") ? profile.get("name").getAsString() : "玩家";
                    String kind = profile.has("kind") ? profile.get("kind").getAsString() : "";
                    profiles.add(name + ("HOST".equals(kind) ? "（房主）" : ""));
                }
            }
        }

        private RoomState(String message) {
            state = "unavailable";
            room = "";
            url = "";
            this.message = message == null ? "无法连接陶瓦联机服务" : message;
            profiles = new ArrayList<String>();
        }

        private static RoomState failure(String message) {
            return new RoomState(message);
        }

        private static String exceptionMessage(int type) {
            switch (type) {
                case 0:
                    return "无法连接房主，房间可能已关闭";
                case 1:
                    return "与房主的连接中断";
                case 2:
                    return "本机联机节点异常退出";
                case 3:
                    return "房主联机节点异常退出";
                case 4:
                    return "房主的 Minecraft 世界已关闭";
                case 5:
                    return "联机协议错误";
                default:
                    return "未知联机错误";
            }
        }
    }
}
