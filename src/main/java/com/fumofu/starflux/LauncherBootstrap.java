package com.fumofu.starflux;

import java.io.IOException;
import java.io.InputStream;
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

public final class LauncherBootstrap {
    private static final String JAVAFX_VERSION = "21.0.6";
    private static final String GSON_VERSION = "2.11.0";
    private static final String MAVEN_CENTRAL = "https://repo.maven.apache.org/maven2/";
    private static final String[] JAVAFX_MODULES = {"javafx-base", "javafx-graphics", "javafx-controls", "javafx-fxml"};

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
            for (String module : JAVAFX_MODULES) {
                modulePath.add(downloadLibrary(
                        module,
                        JAVAFX_VERSION,
                        platform,
                        libraryDirectory,
                        "org/openjfx/" + module + "/" + JAVAFX_VERSION + "/" + module + "-"
                                + JAVAFX_VERSION + "-" + platform + ".jar",
                        true,
                        logFile));
            }
            modulePath.add(downloadLibrary(
                    "gson",
                    GSON_VERSION,
                    null,
                    libraryDirectory,
                    "com/google/code/gson/gson/" + GSON_VERSION + "/gson-" + GSON_VERSION + ".jar",
                    false,
                    logFile));
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
                                        Path logFile) throws IOException {
        String fileName = artifact + "-" + version + (classifier == null ? "" : "-" + classifier) + ".jar";
        Path target = libraryDirectory.resolve(fileName);
        if (isValidJar(target, javafx)) {
            return target;
        }
        Files.deleteIfExists(target);
        Path temporary = target.resolveSibling(fileName + ".part");
        Files.deleteIfExists(temporary);
        log(logFile, "Downloading runtime library " + fileName);

        HttpURLConnection connection = (HttpURLConnection) new URL(MAVEN_CENTRAL + relativeUrl).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(60000);
        connection.setRequestProperty("User-Agent", "StarFlux-MC-Launcher");
        try {
            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw new IOException("下载运行库失败（HTTP " + responseCode + "）：" + relativeUrl);
            }
            try (InputStream input = connection.getInputStream()) {
                Files.copy(input, temporary);
            }
        } finally {
            connection.disconnect();
        }

        if (!isValidJar(temporary, javafx)) {
            Files.deleteIfExists(temporary);
            throw new IOException("下载的运行库文件无效：" + fileName);
        }
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
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
        command.add("com.fumofu.starflux/com.fumofu.starflux.HelloApplication");
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
