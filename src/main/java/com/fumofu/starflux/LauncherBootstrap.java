package com.fumofu.starflux;

import java.awt.BorderLayout;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.jar.JarFile;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

public final class LauncherBootstrap {
    private static final String JAVAFX_VERSION = "21.0.6";
    private static final String GSON_VERSION = "2.11.0";
    private static final String ALIYUN_MAVEN = "https://maven.aliyun.com/repository/central/";
    private static final String MAVEN_CENTRAL = "https://repo.maven.apache.org/maven2/";
    private static final String[] JAVAFX_MODULES = {"javafx-base", "javafx-graphics", "javafx-controls", "javafx-fxml"};
    private static final int RUNTIME_LIBRARY_COUNT = JAVAFX_MODULES.length + 1;

    private LauncherBootstrap() {
    }

    public static void main(String[] args) {
        Path dataDirectory = null;
        try {
            Path applicationJar = applicationJar();
            Path applicationDirectory = applicationJar.getParent();
            dataDirectory = applicationDirectory.resolve(".starflux");
            Path logFile = dataDirectory.resolve("logs").resolve("launcher.log");
            Files.createDirectories(logFile.getParent());
            log(logFile, "StarFlux starting");
            String platform = javafxPlatform();
            Path libraryDirectory = dataDirectory.resolve("lib").resolve(platform);
            Files.createDirectories(libraryDirectory);

            List<Path> modulePath = new ArrayList<Path>();
            try (DownloadProgress progress = new DownloadProgress()) {
                int libraryIndex = 0;
                for (String module : JAVAFX_MODULES) {
                    libraryIndex++;
                    modulePath.add(downloadLibrary(
                            module,
                            JAVAFX_VERSION,
                            platform,
                            libraryDirectory,
                            "org/openjfx/" + module + "/" + JAVAFX_VERSION + "/" + module + "-"
                                    + JAVAFX_VERSION + "-" + platform + ".jar",
                            true,
                            logFile,
                            progress,
                            libraryIndex));
                }
                modulePath.add(downloadLibrary(
                        "gson",
                        GSON_VERSION,
                        null,
                        libraryDirectory,
                        "com/google/code/gson/gson/" + GSON_VERSION + "/gson-" + GSON_VERSION + ".jar",
                        false,
                        logFile,
                        progress,
                        RUNTIME_LIBRARY_COUNT));
                progress.finish();
            }
            modulePath.add(applicationJar);

            launchApplication(applicationJar, dataDirectory, modulePath, logFile);
        } catch (Exception exception) {
            if (dataDirectory != null) {
                try {
                    logException(dataDirectory.resolve("logs").resolve("launcher.log"), exception);
                } catch (IOException loggingException) {
                    exception.addSuppressed(loggingException);
                }
            }
            exception.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static Path applicationJar() throws Exception {
        Path location = Paths.get(LauncherBootstrap.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI()).toAbsolutePath().normalize();
        if (Files.isRegularFile(location)) {
            return location;
        }
        throw new IOException("请使用 java -jar 启动 StarFlux 发布包：" + location);
    }

    private static String javafxPlatform() throws IOException {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        boolean arm64 = arch.equals("aarch64") || arch.equals("arm64");
        boolean x64 = arch.equals("amd64") || arch.equals("x86_64");
        if (os.contains("win")) {
            if (!x64) {
                throw new IOException("当前 Windows 处理器架构暂不支持自动下载 JavaFX 运行库：" + arch);
            }
            return "win";
        }
        if (os.contains("mac") || os.contains("darwin")) {
            if (!arm64 && !x64) {
                throw new IOException("当前 macOS 处理器架构暂不支持自动下载 JavaFX 运行库：" + arch);
            }
            return arm64 ? "mac-aarch64" : "mac";
        }
        if (os.contains("linux")) {
            if (!arm64 && !x64) {
                throw new IOException("当前 Linux 处理器架构暂不支持自动下载 JavaFX 运行库：" + arch);
            }
            return arm64 ? "linux-aarch64" : "linux";
        }
        throw new IOException("不支持的操作系统：" + System.getProperty("os.name"));
    }

    private static Path downloadLibrary(String artifact, String version, String classifier,
                                        Path libraryDirectory, String relativeUrl, boolean javafx,
                                        Path logFile, DownloadProgress progress, int libraryIndex)
            throws IOException {
        String fileName = artifact + "-" + version + (classifier == null ? "" : "-" + classifier) + ".jar";
        Path target = libraryDirectory.resolve(fileName);
        if (isValidJar(target, javafx)) {
            return target;
        }
        Files.deleteIfExists(target);
        Path temporary = target.resolveSibling(fileName + ".part");
        Files.deleteIfExists(temporary);
        log(logFile, "Downloading runtime library " + fileName);

        IOException downloadFailure = null;
        String[] repositories = {ALIYUN_MAVEN, MAVEN_CENTRAL};
        for (int repositoryIndex = 0; repositoryIndex < repositories.length; repositoryIndex++) {
            String repository = repositories[repositoryIndex];
            String repositoryName = repositoryIndex == 0 ? "阿里云镜像" : "Maven Central";
            progress.startLibrary(fileName, libraryIndex, repositoryName);
            try {
                downloadFromRepository(repository + relativeUrl, temporary, javafx, progress);
                try {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException exception) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                }
                return target;
            } catch (IOException exception) {
                Files.deleteIfExists(temporary);
                if (downloadFailure == null) {
                    downloadFailure = exception;
                } else {
                    downloadFailure.addSuppressed(exception);
                }
                if (repositoryIndex + 1 < repositories.length) {
                    progress.setStatus(fileName + "：阿里云镜像不可用，正在切换 Maven Central...");
                }
            }
        }
        throw new IOException("下载运行库失败（已尝试阿里云镜像和 Maven Central）：" + fileName,
                downloadFailure);
    }

    private static void downloadFromRepository(String url, Path temporary, boolean javafx,
                                               DownloadProgress progress) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(60000);
        connection.setRequestProperty("User-Agent", "StarFlux-MC-Launcher");
        try {
            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw new IOException("下载运行库失败（HTTP " + responseCode + "）：" + url);
            }
            long contentLength = connection.getContentLengthLong();
            try (InputStream input = connection.getInputStream();
                 OutputStream output = Files.newOutputStream(temporary,
                         StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                byte[] buffer = new byte[16384];
                long downloaded = 0;
                long lastUpdate = 0;
                int count;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                    downloaded += count;
                    long now = System.nanoTime();
                    if (now - lastUpdate >= 100_000_000L || (contentLength > 0 && downloaded >= contentLength)) {
                        progress.updateBytes(downloaded, contentLength);
                        lastUpdate = now;
                    }
                }
            }
        } finally {
            connection.disconnect();
        }
        if (!isValidJar(temporary, javafx)) {
            throw new IOException("下载的运行库文件无效");
        }
    }

    private static final class DownloadProgress implements AutoCloseable {
        private volatile JFrame frame;
        private JLabel statusLabel;
        private JProgressBar progressBar;
        private volatile boolean finished;

        void startLibrary(String fileName, int index, String repository) {
            updateStatus("正在下载运行库（" + index + "/" + RUNTIME_LIBRARY_COUNT + "）："
                    + fileName + " · " + repository);
        }

        void setStatus(String message) {
            updateStatus(message);
        }

        void updateBytes(long downloaded, long total) {
            runOnEventThread(() -> {
                if (finished) {
                    return;
                }
                ensureWindow();
                if (frame == null) {
                    return;
                }
                if (total > 0) {
                    progressBar.setIndeterminate(false);
                    progressBar.setValue((int) Math.min(1000, downloaded * 1000 / total));
                    progressBar.setString(String.format(Locale.ROOT, "%.0f%%  (%s / %s)",
                            100.0 * downloaded / total, formatBytes(downloaded), formatBytes(total)));
                } else {
                    progressBar.setIndeterminate(true);
                    progressBar.setString(formatBytes(downloaded));
                }
            });
        }

        void finish() {
            finished = true;
            runOnEventThread(() -> {
                if (frame != null) {
                    frame.setVisible(false);
                    frame.dispose();
                    frame = null;
                }
            });
        }

        private void updateStatus(String message) {
            runOnEventThread(() -> {
                if (finished) {
                    return;
                }
                ensureWindow();
                if (frame == null) {
                    return;
                }
                statusLabel.setText("<html><div style='width:420px'>" + escapeHtml(message) + "</div></html>");
                progressBar.setIndeterminate(true);
                progressBar.setString("");
            });
        }

        private void ensureWindow() {
            if (finished || frame != null || GraphicsEnvironment.isHeadless()) {
                return;
            }
            frame = new JFrame("StarFlux MC Launcher · 首次启动");
            frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
            statusLabel = new JLabel("正在准备运行库...");
            progressBar = new JProgressBar(0, 1000);
            progressBar.setStringPainted(true);
            JPanel content = new JPanel(new BorderLayout(0, 12));
            content.setBorder(javax.swing.BorderFactory.createEmptyBorder(18, 20, 18, 20));
            content.add(statusLabel, BorderLayout.NORTH);
            content.add(progressBar, BorderLayout.CENTER);
            frame.setContentPane(content);
            frame.setSize(500, 125);
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
        }

        private void runOnEventThread(Runnable action) {
            if (GraphicsEnvironment.isHeadless()) {
                return;
            }
            if (SwingUtilities.isEventDispatchThread()) {
                action.run();
            } else {
                SwingUtilities.invokeLater(action);
            }
        }

        private static String formatBytes(long bytes) {
            return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024));
        }

        private static String escapeHtml(String text) {
            return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }

        @Override
        public void close() {
            finish();
        }
    }

    private static boolean isValidJar(Path file, boolean requireModuleDescriptor) {
        if (!Files.isRegularFile(file)) {
            return false;
        }
        try (JarFile jar = new JarFile(file.toFile())) {
            return requireModuleDescriptor
                    ? jar.getEntry("module-info.class") != null
                    : jar.getManifest() != null;
        } catch (IOException exception) {
            return false;
        }
    }

    private static void launchApplication(Path applicationJar, Path dataDirectory, List<Path> modulePath,
                                          Path logFile) throws IOException, InterruptedException {
        Path javaExecutable = Paths.get(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java");
        if (!Files.isRegularFile(javaExecutable)) {
            throw new IOException("找不到 Java 可执行文件：" + javaExecutable);
        }
        StringBuilder modulePathArgument = new StringBuilder();
        for (Path path : modulePath) {
            if (modulePathArgument.length() > 0) {
                modulePathArgument.append(java.io.File.pathSeparator);
            }
            modulePathArgument.append(path.toAbsolutePath());
        }

        List<String> command = new ArrayList<String>();
        command.add(javaExecutable.toString());
        command.add("-Dstarflux.home=" + dataDirectory.toAbsolutePath());
        command.add("--module-path");
        command.add(modulePathArgument.toString());
        command.add("--enable-native-access=javafx.graphics");
        command.add("--module");
        command.add("com.fumofu.starflux/com.fumofu.starflux.app.HelloApplication");
        log(logFile, "Launching JavaFX application");
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(applicationJar.getParent().toFile());
        processBuilder.redirectErrorStream(true);
        processBuilder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
        int exitCode = processBuilder.start().waitFor();
        if (exitCode != 0) {
            log(logFile, "Application exited with code " + exitCode);
            throw new IOException("启动器进程异常退出（代码 " + exitCode + "），请查看 " + logFile);
        }
        log(logFile, "StarFlux closed");
    }

    private static void log(Path logFile, String message) throws IOException {
        Files.createDirectories(logFile.getParent());
        Files.writeString(logFile, Instant.now() + " " + message + System.lineSeparator(),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static void logException(Path logFile, Exception exception) throws IOException {
        StringWriter stackTrace = new StringWriter();
        exception.printStackTrace(new PrintWriter(stackTrace));
        Files.createDirectories(logFile.getParent());
        Files.writeString(logFile, Instant.now() + " Startup failed:" + System.lineSeparator()
                        + stackTrace + System.lineSeparator(),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
