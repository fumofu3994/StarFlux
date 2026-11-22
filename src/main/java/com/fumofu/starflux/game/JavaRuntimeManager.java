package com.fumofu.starflux.game;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class JavaRuntimeManager {
    private static final Pattern VERSION_PATTERN = Pattern.compile("version\\s+\"([^\"]+)\"");

    private JavaRuntimeManager() {
    }

    public static List<JavaInstallation> detectInstalled() {
        Map<Path, JavaInstallation> found = new LinkedHashMap<>();
        addHome(found, System.getProperty("java.home"));
        addHome(found, System.getenv("JAVA_HOME"));
        addHome(found, System.getenv("JDK_HOME"));

        String pathValue = System.getenv("PATH");
        if (pathValue != null) {
            for (String item : pathValue.split(Pattern.quote(System.getProperty("path.separator")))) {
                if (!item.trim().isEmpty()) {
                    addExecutable(found, Paths.get(item.trim()).resolve(javaExecutableName()));
                }
            }
        }

        if (isWindows()) {
            addWindowsInstallations(found, System.getenv("ProgramFiles"));
            addWindowsInstallations(found, System.getenv("ProgramFiles(x86)"));
            addWindowsInstallations(found, System.getenv("LOCALAPPDATA"));
        } else {
            addUnixInstallations(found, "/usr/lib/jvm");
            addUnixInstallations(found, "/Library/Java/JavaVirtualMachines");
        }
        addManagedInstallations(found, managedJavaDirectory());

        List<JavaInstallation> installations = new ArrayList<>(found.values());
        installations.sort(Comparator.comparingInt(JavaInstallation::getMajorVersion).reversed()
                .thenComparing(JavaInstallation::getExecutablePath));
        return installations;
    }

    public static int requiredFeature(String minecraftVersion) {
        Matcher matcher = Pattern.compile("^(?:1\\.)?(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?")
                .matcher(minecraftVersion);
        if (!matcher.find()) {
            return 21;
        }
        int major = Integer.parseInt(matcher.group(1));
        int minor = matcher.group(2) == null ? 0 : Integer.parseInt(matcher.group(2));
        int patch = matcher.group(3) == null ? 0 : Integer.parseInt(matcher.group(3));
        if (major == 1) {
            return minor > 20 || (minor == 20 && patch >= 5) ? 21
                    : minor >= 18 ? 17 : minor >= 17 ? 16 : 8;
        }
        return 21;
    }

    public static JavaInstallation findCompatible(List<JavaInstallation> installations, int requiredFeature) {
        JavaInstallation exactMatch = installations.stream()
                .filter(installation -> installation.getMajorVersion() == requiredFeature)
                .findFirst()
                .orElse(null);
        if (exactMatch != null) {
            return exactMatch;
        }
        return installations.stream()
                .filter(installation -> installation.getMajorVersion() >= requiredFeature)
                .min(Comparator.comparingInt(JavaInstallation::getMajorVersion))
                .orElse(null);
    }

    public static JavaInstallation inspectExecutable(Path executable) throws IOException {
        Path normalized = executable.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized) || !Files.isExecutable(normalized)) {
            throw new IOException("Java 可执行文件不存在或无法运行：" + normalized);
        }
        JavaInstallation installation = inspect(normalized);
        if (installation == null) {
            throw new IOException("无法读取 Java 版本信息：" + normalized);
        }
        return installation;
    }

    private static void addWindowsInstallations(Map<Path, JavaInstallation> found, String parent) {
        if (parent == null || parent.trim().isEmpty()) {
            return;
        }
        for (String child : new String[]{"Eclipse Adoptium", "Java", "Microsoft", "Amazon Corretto", "Zulu"}) {
            Path directory = Paths.get(parent, child);
            if (!Files.isDirectory(directory)) {
                continue;
            }
            try (var paths = Files.walk(directory, 4)) {
                paths.filter(path -> path.getFileName() != null
                                && path.getFileName().toString().equalsIgnoreCase(javaExecutableName()))
                        .forEach(path -> addExecutable(found, path));
            } catch (IOException ignored) {
                // A missing or unreadable optional installation directory does not block other discovery sources.
            }
        }
    }

    private static void addUnixInstallations(Map<Path, JavaInstallation> found, String directory) {
        Path root = Paths.get(directory);
        if (!Files.isDirectory(root)) {
            return;
        }
        try (var paths = Files.walk(root, 5)) {
            paths.filter(path -> path.getFileName() != null
                            && path.getFileName().toString().equals(javaExecutableName()))
                    .forEach(path -> addExecutable(found, path));
        } catch (IOException ignored) {
            // A missing or unreadable optional installation directory does not block other discovery sources.
        }
    }

    private static void addManagedInstallations(Map<Path, JavaInstallation> found, Path directory) {
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (var paths = Files.walk(directory, 8)) {
            paths.filter(path -> path.getFileName() != null
                            && path.getFileName().toString().equalsIgnoreCase(javaExecutableName()))
                    .forEach(path -> addExecutable(found, path));
        } catch (IOException ignored) {
            // Managed runtimes are an optional discovery source.
        }
    }

    static Path managedJavaDirectory() {
        String configured = System.getProperty("starflux.home");
        Path home = configured == null || configured.trim().isEmpty()
                ? Paths.get(System.getProperty("user.home"), ".starflux")
                : Paths.get(configured);
        return home.toAbsolutePath().normalize().resolve("java");
    }

    private static void addHome(Map<Path, JavaInstallation> found, String home) {
        if (home == null || home.trim().isEmpty()) {
            return;
        }
        addExecutable(found, Paths.get(home).resolve("bin").resolve(javaExecutableName()));
        addExecutable(found, Paths.get(home).resolve("Contents").resolve("Home")
                .resolve("bin").resolve(javaExecutableName()));
    }

    private static void addExecutable(Map<Path, JavaInstallation> found, Path executable) {
        try {
            Path normalized = executable.toAbsolutePath().normalize();
            if (!Files.isRegularFile(normalized) || !Files.isExecutable(normalized)) {
                return;
            }
            JavaInstallation installation = inspect(normalized);
            if (installation != null) {
                found.putIfAbsent(normalized, installation);
            }
        } catch (IOException | RuntimeException ignored) {
            // Invalid Java binaries are excluded from the selectable runtime list.
        }
    }

    private static JavaInstallation inspect(Path executable) throws IOException {
        Process process = new ProcessBuilder(executable.toString(), "-version")
                .redirectErrorStream(true).start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append(' ');
            }
        }
        try {
            if (!process.waitFor(3, TimeUnit.SECONDS) || process.exitValue() != 0) {
                process.destroyForcibly();
                return null;
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return null;
        }

        Matcher matcher = VERSION_PATTERN.matcher(output);
        if (!matcher.find()) {
            return null;
        }
        String version = matcher.group(1);
        String feature = version.startsWith("1.")
                ? version.substring(2).split("[._+-]")[0]
                : version.split("[._+-]")[0];
        try {
            return new JavaInstallation(executable, version, Integer.parseInt(feature));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static String javaExecutableName() {
        return isWindows() ? "java.exe" : "java";
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    }

    public static final class JavaInstallation {
        private final Path executablePath;
        private final String version;
        private final int majorVersion;

        private JavaInstallation(Path executablePath, String version, int majorVersion) {
            this.executablePath = executablePath;
            this.version = version;
            this.majorVersion = majorVersion;
        }

        public Path getExecutablePath() {
            return executablePath;
        }

        public String getVersion() {
            return version;
        }

        public int getMajorVersion() {
            return majorVersion;
        }

        @Override
        public String toString() {
            return "Java " + majorVersion + " (" + version + ") · " + executablePath;
        }
    }

    public interface ProgressListener {
        void onProgress(double progress, String message);
    }
}
