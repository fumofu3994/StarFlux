package com.fumofu.starflux.app;

import com.fumofu.starflux.account.AccountAuthenticator;
import com.fumofu.starflux.account.AccountStore;
import com.fumofu.starflux.account.AccountType;
import com.fumofu.starflux.account.MinecraftSession;
import com.fumofu.starflux.config.LauncherData;
import com.fumofu.starflux.download.MinecraftInstaller;
import com.fumofu.starflux.download.MirrorSource;
import com.fumofu.starflux.game.JavaRuntimeManager;
import com.fumofu.starflux.game.LoaderType;
import com.fumofu.starflux.game.MinecraftGameLauncher;
import com.fumofu.starflux.game.TemurinRuntimeInstaller;
import com.fumofu.starflux.multiplayer.TerracottaService;
import com.fumofu.starflux.ui.LoaderInstallDialog;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.PasswordField;
import javafx.scene.control.Slider;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.TextField;
import javafx.scene.control.TextArea;
import javafx.scene.layout.GridPane;
import javafx.geometry.Insets;
import javafx.scene.layout.StackPane;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import com.sun.management.OperatingSystemMXBean;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.nio.file.FileVisitResult;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.Files;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.concurrent.CompletionException;

public class LauncherController {
    @FXML
    private StackPane pageContainer;
    @FXML
    private javafx.scene.layout.VBox playPage;
    @FXML
    private javafx.scene.layout.VBox downloadPage;
    @FXML
    private javafx.scene.layout.VBox settingsPage;
    @FXML
    private javafx.scene.layout.VBox multiplayerPage;
    @FXML
    private javafx.scene.layout.VBox accountsPage;
    @FXML
    private Button playNavButton;
    @FXML
    private Button downloadNavButton;
    @FXML
    private Button settingsNavButton;
    @FXML
    private Button multiplayerNavButton;
    @FXML
    private ComboBox<MirrorSource> mirrorComboBox;
    @FXML
    private ComboBox<MinecraftInstaller.VersionEntry> versionComboBox;
    @FXML
    private ComboBox<AccountStore.Account> accountComboBox;
    @FXML
    private ListView<InstalledProfile> profileListView;
    @FXML
    private Label installedProfilesLabel;
    @FXML
    private ListView<AccountStore.Account> accountListView;
    @FXML
    private Label accountEntryNameLabel;
    @FXML
    private Label accountEntryTypeLabel;
    @FXML
    private TextField microsoftClientIdField;
    @FXML
    private Button microsoftLoginButton;
    @FXML
    private Button installButton;
    @FXML
    private Button launchButton;
    @FXML
    private Button deleteProfileButton;
    @FXML
    private Button configureProfileButton;
    @FXML
    private Button renameInstanceButton;
    @FXML
    private Button installJavaButton;
    @FXML
    private ProgressBar progressBar;
    @FXML
    private Label statusLabel;
    @FXML
    private Label gameDirectoryLabel;
    @FXML
    private TextField gameDirectoryField;
    @FXML
    private TextField roomCodeField;
    @FXML
    private Label roomStateLabel;
    @FXML
    private Label roomCodeLabel;
    @FXML
    private Label roomMembersLabel;
    @FXML
    private Button createRoomButton;
    @FXML
    private Button joinRoomButton;
    @FXML
    private Button leaveRoomButton;
    @FXML
    private Slider memorySlider;
    @FXML
    private TextField memoryField;
    @FXML
    private ComboBox<JavaChoice> javaRuntimeComboBox;
    @FXML
    private ComboBox<String> downloadJavaVersionComboBox;
    @FXML
    private TextField jvmArgumentsField;
    @FXML
    private Label systemMemoryLabel;
    @FXML
    private javafx.scene.control.CheckBox isolateVersionCheckBox;
    @FXML
    private TextField instanceNameField;

    private final AccountStore accountStore = new AccountStore();
    private final AccountAuthenticator accountAuthenticator = new AccountAuthenticator();
    private final MinecraftGameLauncher gameLauncher = new MinecraftGameLauncher();
    private final TerracottaService terracottaService = new TerracottaService();
    private final Map<Path, JavaRuntimeManager.JavaInstallation> javaInstallations = new LinkedHashMap<>();
    private boolean updatingJavaChoices;
    private String suggestedInstanceName = "";
    private static final String GAME_DIRECTORY_KEY = "game-directory";
    private Process gameProcess;

    public void close() {
        terracottaService.close();
    }

    private static final class JavaChoice {
        private final String label;
        private final Path path;
        private final boolean inherit;

        private JavaChoice(String label, Path path) {
            this(label, path, false);
        }

        private JavaChoice(String label, Path path, boolean inherit) {
            this.label = label;
            this.path = path;
            this.inherit = inherit;
        }

        private static JavaChoice automatic() {
            return new JavaChoice("自动（按 Minecraft 版本选择兼容 Java）", null);
        }

        private static JavaChoice inherit() {
            return new JavaChoice("继承全局 Java 设置", null, true);
        }

        @Override
        public String toString() {
            return label;
        }
    }

    @FXML
    private void initialize() {
        gameDirectoryField.setText(getGameDirectory().toAbsolutePath().toString());
        updateGameDirectoryLabel();

        mirrorComboBox.getItems().setAll(MirrorSource.values());
        initializeMemoryControls();
        javaRuntimeComboBox.valueProperty().addListener((_, _, choice) -> {
            if (!updatingJavaChoices && choice != null) {
                saveConfig("java-path", choice.path == null ? "" : choice.path.toString());
            }
        });
        jvmArgumentsField.setText(LauncherData.get("jvm-arguments", ""));
        jvmArgumentsField.textProperty().addListener((_, _, value) -> saveConfig("jvm-arguments", value));
        versionComboBox.valueProperty().addListener((_, _, version) -> {
            if (version != null && (instanceNameField.getText().isBlank()
                    || instanceNameField.getText().equals(suggestedInstanceName))) {
                instanceNameField.setText(version.getId());
                suggestedInstanceName = version.getId();
            }
        });
        downloadJavaVersionComboBox.getItems().setAll("8", "17", "21", "25");
        downloadJavaVersionComboBox.getSelectionModel().select("21");
        refreshJavaInstallations();
        String savedMirror = LauncherData.get("download-mirror", MirrorSource.MOJANG.name());
        try {
            mirrorComboBox.getSelectionModel().select(MirrorSource.valueOf(savedMirror));
        } catch (IllegalArgumentException exception) {
            mirrorComboBox.getSelectionModel().select(MirrorSource.MOJANG);
        }

        mirrorComboBox.valueProperty().addListener((_, oldValue, newValue) -> {
            if (newValue != null && newValue != oldValue) {
                saveConfig("download-mirror", newValue.name());
                if (!versionComboBox.getItems().isEmpty()) {
                    loadVersions(newValue);
                }
            }
        });
        profileListView.getSelectionModel().selectedItemProperty().addListener((_, _, _) -> {
            updateLaunchButton();
            updateInstalledProfileActions();
        });
        accountComboBox.valueProperty().addListener((_, _, newValue) -> {
            if (newValue != null) {
                try {
                    accountStore.select(newValue.getId());
                } catch (UncheckedIOException exception) {
                    statusLabel.setText("保存账号设置失败：" + messageFor(exception));
                }
            }
            if (accountListView != null) {
                refreshAccountList();
            }
            updateAccountEntry();
            updateLaunchButton();
        });
        microsoftClientIdField.setText(LauncherData.get("microsoft-client-id", ""));
        gameDirectoryField.textProperty().addListener((_, _, newValue) -> {
            if (newValue != null && !newValue.trim().isEmpty()) {
                saveConfig(GAME_DIRECTORY_KEY, newValue.trim());
                updateGameDirectoryLabel();
            }
        });
        refreshAccounts();
        refreshInstalledProfiles(null);
        terracottaService.setStateListener(state -> Platform.runLater(() -> updateTerracottaState(state)));
        terracottaService.setProgressListener((progress, message) -> Platform.runLater(() -> {
            progressBar.setProgress(progress);
            if (message != null && !message.isEmpty()) {
                statusLabel.setText(message);
            }
            if (progress >= 1.0) {
                progressBar.setProgress(0);
            }
        }));
        terracottaService.startPolling();
        showPlayPage();
        loadVersions(mirrorComboBox.getValue());
    }

    @FXML
    private void onRefreshJavaClick() {
        refreshJavaInstallations();
    }

    @FXML
    private void onBrowseJavaClick() {
        browseForJava(javaRuntimeComboBox);
    }

    @FXML
    private void onConfigureProfileClick() {
        InstalledProfile profile = profileListView.getSelectionModel().getSelectedItem();
        if (profile == null) {
            statusLabel.setText("请先选择要配置的版本");
            return;
        }

        String prefix = profileSettingsPrefix(profile);
        ComboBox<JavaChoice> javaChoiceBox = new ComboBox<>();
        javaChoiceBox.getItems().add(JavaChoice.inherit());
        javaChoiceBox.getItems().add(JavaChoice.automatic());
        for (JavaRuntimeManager.JavaInstallation installation : javaInstallations.values()) {
            javaChoiceBox.getItems().add(new JavaChoice(installation.toString(),
                    installation.getExecutablePath()));
        }
        String javaPath = LauncherData.get(prefix + "java", "");
        JavaChoice selectedJava = javaChoiceBox.getItems().get(0);
        if ("auto".equals(javaPath)) {
            selectedJava = javaChoiceBox.getItems().get(1);
        } else if (!javaPath.isBlank()) {
            Path selectedPath = Paths.get(javaPath).toAbsolutePath().normalize();
            selectedJava = javaChoiceBox.getItems().stream()
                    .filter(choice -> selectedPath.equals(choice.path))
                    .findFirst()
                    .orElse(new JavaChoice("自定义 Java · " + selectedPath, selectedPath));
            if (!javaChoiceBox.getItems().contains(selectedJava)) {
                javaChoiceBox.getItems().add(selectedJava);
            }
        }
        javaChoiceBox.getSelectionModel().select(selectedJava);

        TextField memoryOverride = new TextField(LauncherData.get(prefix + "memory-gb", ""));
        memoryOverride.setPromptText("留空则使用全局值（当前 " + selectedMemoryGb() + " GB）");
        memoryOverride.setTextFormatter(new javafx.scene.control.TextFormatter<String>(
                change -> change.getControlNewText().matches("\\d{0,2}") ? change : null));
        TextArea argumentsOverride = new TextArea(LauncherData.get(prefix + "jvm-arguments", ""));
        argumentsOverride.setPromptText("留空则使用全局 JVM 参数");
        argumentsOverride.setPrefRowCount(3);
        Button browseButton = new Button("浏览 Java…");
        browseButton.setOnAction(_ -> browseForJava(javaChoiceBox));

        GridPane content = new GridPane();
        content.setHgap(10);
        content.setVgap(10);
        content.setPadding(new Insets(12));
        content.addRow(0, new Label("版本"), new Label(profile.id));
        content.addRow(1, new Label("Java"), javaChoiceBox, browseButton);
        content.addRow(2, new Label("最大内存（GB）"), memoryOverride);
        content.addRow(3, new Label("自定义 JVM 参数"), argumentsOverride);

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("版本启动设置");
        dialog.setHeaderText("为 " + profile.id + " 配置独立 Java 与启动参数");
        dialog.getDialogPane().setContent(content);
        ButtonType saveButton = new ButtonType("保存", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().setAll(saveButton, ButtonType.CANCEL);
        Optional<ButtonType> result = dialog.showAndWait();
        if (result.isEmpty() || result.get() != saveButton) {
            return;
        }

        String memory = memoryOverride.getText().trim();
        if (!memory.isEmpty()) {
            try {
                int amount = Integer.parseInt(memory);
                if (amount < 1 || amount > 64) {
                    statusLabel.setText("该版本最大内存须为 1–64 GB");
                    return;
                }
            } catch (NumberFormatException exception) {
                statusLabel.setText("该版本的最大内存值无效");
                return;
            }
        }
        JavaChoice choice = javaChoiceBox.getValue();
        saveConfig(prefix + "java", choice == null || choice.inherit ? ""
                : choice.path == null ? "auto" : choice.path.toString());
        saveConfig(prefix + "memory-gb", memory);
        saveConfig(prefix + "jvm-arguments", argumentsOverride.getText().trim());
        statusLabel.setText("已保存 " + profile.id + " 的独立 Java 与启动参数");
    }

    @FXML
    private void onRenameInstanceClick() {
        InstalledProfile profile = profileListView.getSelectionModel().getSelectedItem();
        if (profile == null || !profile.isolated) {
            statusLabel.setText("请选择一个独立实例再重命名");
            return;
        }
        TextInputDialog dialog = new TextInputDialog(profile.instanceName);
        dialog.setTitle("重命名实例");
        dialog.setHeaderText("为实例设置一个容易识别的名称");
        dialog.setContentText("实例名称：");
        Optional<String> result = dialog.showAndWait();
        if (result.isEmpty()) {
            return;
        }
        String name = result.get().trim();
        try {
            validateInstanceName(name);
            writeInstanceMetadata(profile.gameDirectory, name, minecraftVersionForProfile(profile));
            statusLabel.setText("实例已重命名为“" + name + "”");
            refreshInstalledProfiles(null);
        } catch (IOException | IllegalArgumentException exception) {
            statusLabel.setText("重命名实例失败：" + messageFor(exception));
        }
    }

    @FXML
    private void onDownloadJavaClick() {
        String version = downloadJavaVersionComboBox.getValue();
        if (version == null) {
            statusLabel.setText("请选择要下载的 Java 版本");
            return;
        }
        int majorVersion = Integer.parseInt(version);
        installJavaButton.setDisable(true);
        progressBar.setProgress(-1);
        statusLabel.setText("正在从 Eclipse Temurin 下载并安装 Java " + majorVersion + "...");
        Task<JavaRuntimeManager.JavaInstallation> task = new Task<>() {
            @Override
            protected JavaRuntimeManager.JavaInstallation call() throws Exception {
                return TemurinRuntimeInstaller.install(majorVersion, (progress, message) ->
                        Platform.runLater(() -> {
                            progressBar.setProgress(progress);
                            statusLabel.setText(message);
                        }));
            }
        };
        task.setOnSucceeded(_ -> {
            JavaRuntimeManager.JavaInstallation installation = task.getValue();
            javaInstallations.put(installation.getExecutablePath(), installation);
            updateJavaChoices(LauncherData.get("java-path", ""));
            installJavaButton.setDisable(false);
            progressBar.setProgress(0);
            statusLabel.setText("Java " + majorVersion + " 已安装并加入运行时列表；可在全局或实例设置中选择。");
        });
        task.setOnFailed(_ -> {
            installJavaButton.setDisable(false);
            progressBar.setProgress(0);
            statusLabel.setText("安装 Java 失败：" + messageFor(task.getException()));
        });
        Thread thread = new Thread(task, "temurin-java-install-" + majorVersion);
        thread.setDaemon(true);
        thread.start();
    }

    private void refreshJavaInstallations() {
        statusLabel.setText("正在检测 JAVA_HOME、PATH 和常见目录中的 Java...");
        Task<List<JavaRuntimeManager.JavaInstallation>> task = new Task<>() {
            @Override
            protected List<JavaRuntimeManager.JavaInstallation> call() {
                return JavaRuntimeManager.detectInstalled();
            }
        };
        task.setOnSucceeded(_ -> {
            javaInstallations.clear();
            for (JavaRuntimeManager.JavaInstallation installation : task.getValue()) {
                javaInstallations.put(installation.getExecutablePath(), installation);
            }
            updateJavaChoices(LauncherData.get("java-path", ""));
            statusLabel.setText("已检测到 " + javaInstallations.size() + " 个 Java 运行环境");
        });
        task.setOnFailed(_ -> statusLabel.setText("检测 Java 失败：" + messageFor(task.getException())));
        Thread thread = new Thread(task, "java-runtime-detection");
        thread.setDaemon(true);
        thread.start();
    }

    private void updateJavaChoices(String selectedPath) {
        updatingJavaChoices = true;
        try {
            List<JavaChoice> choices = new ArrayList<>();
            choices.add(JavaChoice.automatic());
            for (JavaRuntimeManager.JavaInstallation installation : javaInstallations.values()) {
                choices.add(new JavaChoice(installation.toString(), installation.getExecutablePath()));
            }
            Path selected = selectedPath == null || selectedPath.isBlank()
                    ? null : Paths.get(selectedPath).toAbsolutePath().normalize();
            JavaChoice selectedChoice = choices.get(0);
            for (JavaChoice choice : choices) {
                if (selected != null && selected.equals(choice.path)) {
                    selectedChoice = choice;
                    break;
                }
            }
            if (selected != null && selectedChoice.path == null) {
                selectedChoice = new JavaChoice("自定义 Java · " + selected, selected);
                choices.add(selectedChoice);
            }
            javaRuntimeComboBox.getItems().setAll(choices);
            javaRuntimeComboBox.getSelectionModel().select(selectedChoice);
        } finally {
            updatingJavaChoices = false;
        }
    }

    private void browseForJava(ComboBox<JavaChoice> target) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("选择 Java 可执行文件");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                "Java", System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java"));
        java.io.File selected = chooser.showOpenDialog(pageContainer.getScene().getWindow());
        if (selected == null) {
            return;
        }
        Path executable = selected.toPath().toAbsolutePath().normalize();
        try {
            JavaRuntimeManager.JavaInstallation installation = JavaRuntimeManager.inspectExecutable(executable);
            javaInstallations.put(executable, installation);
            JavaChoice choice = new JavaChoice(installation.toString(), executable);
            if (!target.getItems().contains(choice)) {
                target.getItems().add(choice);
            }
            target.getSelectionModel().select(choice);
        } catch (IOException exception) {
            statusLabel.setText("所选文件不是可用的 Java：" + messageFor(exception));
        }
    }

    private int selectedMemoryGb() {
        commitMemoryField((int) Math.round(memorySlider.getMax()));
        return (int) Math.round(memorySlider.getValue());
    }

    private static String profileSettingsPrefix(InstalledProfile profile) {
        String location = profile.isolated ? "instance" : "shared";
        if (profile.isolated && profile.gameDirectory.getFileName() != null
                && !profile.gameDirectory.getFileName().toString().equals(profile.id)) {
            return "profile.instance." + profile.gameDirectory.getFileName() + "." + profile.id + ".";
        }
        return "profile." + location + "." + profile.id + ".";
    }

    private void initializeMemoryControls() {
        long totalMemoryBytes = ((OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean())
                .getTotalMemorySize();
        double totalMemoryGb = totalMemoryBytes / (1024.0 * 1024 * 1024);
        int maxMemoryGb = Math.max(1, Math.min(64, (int) Math.floor(totalMemoryGb)));
        systemMemoryLabel.setText(String.format(java.util.Locale.ROOT,
                "检测到系统内存：%.1f GB", totalMemoryGb));
        memorySlider.setMin(1);
        memorySlider.setMax(maxMemoryGb);
        memorySlider.setMajorTickUnit(Math.max(1, Math.min(8, maxMemoryGb - 1)));
        memorySlider.setBlockIncrement(1);
        memorySlider.setMinorTickCount(Math.max(0, (int) memorySlider.getMajorTickUnit() - 1));
        memorySlider.setSnapToTicks(true);

        int configuredMemory;
        try {
            configuredMemory = Integer.parseInt(LauncherData.get("max-memory-gb", "4"));
        } catch (NumberFormatException exception) {
            configuredMemory = 4;
        }
        configuredMemory = Math.max(1, Math.min(maxMemoryGb, configuredMemory));
        memorySlider.setValue(configuredMemory);
        memoryField.setText(Integer.toString(configuredMemory));
        memoryField.setTextFormatter(new javafx.scene.control.TextFormatter<String>(
                change -> change.getControlNewText().matches("\\d{0,2}") ? change : null));
        memorySlider.valueProperty().addListener((_, _, value) -> {
            int memory = (int) Math.round(value.doubleValue());
            memoryField.setText(Integer.toString(memory));
            saveConfig("max-memory-gb", Integer.toString(memory));
        });
        memoryField.textProperty().addListener((_, _, value) -> {
            if (value != null && !value.isEmpty()) {
                int memory = Integer.parseInt(value);
                if (memory > maxMemoryGb) {
                    memorySlider.setValue(maxMemoryGb);
                    memoryField.setText(Integer.toString(maxMemoryGb));
                } else if (memory >= 1) {
                    memorySlider.setValue(memory);
                }
            }
        });
        memoryField.setOnAction(_ -> commitMemoryField(maxMemoryGb));
        memoryField.focusedProperty().addListener((_, _, focused) -> {
            if (!focused) {
                commitMemoryField(maxMemoryGb);
            }
        });
        saveConfig("max-memory-gb", Integer.toString(configuredMemory));
    }

    private void commitMemoryField(int maxMemoryGb) {
        int memory;
        try {
            memory = Integer.parseInt(memoryField.getText());
        } catch (NumberFormatException exception) {
            memory = (int) Math.round(memorySlider.getValue());
        }
        memory = Math.max(1, Math.min(maxMemoryGb, memory));
        memorySlider.setValue(memory);
        memoryField.setText(Integer.toString(memory));
    }

    @FXML
    private void showPlayPage() {
        showPage(playPage, playNavButton);
    }

    @FXML
    private void showDownloadPage() {
        showPage(downloadPage, downloadNavButton);
        if (versionComboBox.getItems().isEmpty() && !versionComboBox.isDisabled()) {
            loadVersions(mirrorComboBox.getValue());
        }
    }

    @FXML
    private void showSettingsPage() {
        showPage(settingsPage, settingsNavButton);
    }

    @FXML
    private void showMultiplayerPage() {
        showPage(multiplayerPage, multiplayerNavButton);
    }

    @FXML
    private void showAccountsPage() {
        showPage(accountsPage, null);
    }

    private void showPage(javafx.scene.layout.VBox page, Button activeButton) {
        playPage.setVisible(page == playPage);
        playPage.setManaged(page == playPage);
        downloadPage.setVisible(page == downloadPage);
        downloadPage.setManaged(page == downloadPage);
        settingsPage.setVisible(page == settingsPage);
        settingsPage.setManaged(page == settingsPage);
        multiplayerPage.setVisible(page == multiplayerPage);
        multiplayerPage.setManaged(page == multiplayerPage);
        playNavButton.getStyleClass().setAll("nav-button");
        downloadNavButton.getStyleClass().setAll("nav-button");
        settingsNavButton.getStyleClass().setAll("nav-button");
        multiplayerNavButton.getStyleClass().setAll("nav-button");
        accountsPage.setVisible(page == accountsPage);
        accountsPage.setManaged(page == accountsPage);
        if (activeButton != null) {
            activeButton.getStyleClass().setAll("nav-button", "nav-button-active");
        }
    }

    @FXML
    private void onInstallLoaderClick() {
        MinecraftInstaller.VersionEntry minecraftVersion = versionComboBox.getValue();
        if (minecraftVersion == null) {
            statusLabel.setText("请先选择 Minecraft 版本");
            return;
        }

        try {
            Path installDirectory = getInstallGameDirectory();
            Optional<LoaderType> installed = new LoaderInstallDialog().showAndWait(
                    launchButton.getScene().getWindow(), minecraftVersion.getId(), installDirectory);
            if (installed.isPresent()) {
                if (isolateVersionCheckBox.isSelected()) {
                    writeInstanceMetadata(installDirectory, instanceNameField.getText().trim(),
                            minecraftVersion.getId());
                }
                refreshInstalledProfiles(installed.get().name().toLowerCase());
                selectInstalledProfile(installDirectory, installed.get().name());
                statusLabel.setText(installed.get() + " 安装完成");
            }
        } catch (IOException | IllegalArgumentException exception) {
            statusLabel.setText("准备安装加载器失败：" + messageFor(exception));
        }
    }

    @FXML
    private void onModpackClick() {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("整合包下载");
        alert.setHeaderText("整合包下载尚未接入");
        alert.setContentText("这里将支持搜索并安装 Modrinth 与 CurseForge 整合包。当前可以先安装 Minecraft 本体和模组加载器。");
        alert.showAndWait();
    }

    @FXML
    private void onChooseGameDirectoryClick() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("选择 Minecraft 游戏目录");
        Path current = getGameDirectory();
        if (Files.isDirectory(current)) {
            chooser.setInitialDirectory(current.toFile());
        } else if (current.getParent() != null && Files.isDirectory(current.getParent())) {
            chooser.setInitialDirectory(current.getParent().toFile());
        }
        java.io.File selected = chooser.showDialog(gameDirectoryField.getScene().getWindow());
        if (selected != null) {
            gameDirectoryField.setText(selected.toPath().toAbsolutePath().normalize().toString());
            refreshInstalledProfiles(null);
        }
    }

    private void loadVersions(MirrorSource mirrorSource) {
        final MinecraftInstaller selectedInstaller = new MinecraftInstaller(mirrorSource);
        progressBar.setProgress(-1);
        statusLabel.setText("正在从 " + mirrorSource + " 加载 Minecraft 版本列表...");
        installButton.setDisable(true);
        launchButton.setDisable(true);
        mirrorComboBox.setDisable(true);
        versionComboBox.setDisable(true);

        Task<List<MinecraftInstaller.VersionEntry>> task = new Task<>() {
            @Override
            protected List<MinecraftInstaller.VersionEntry> call() throws Exception {
                return selectedInstaller.fetchVersions();
            }
        };
        task.setOnSucceeded(_ -> {
            versionComboBox.getItems().setAll(task.getValue());
            versionComboBox.setDisable(false);
            mirrorComboBox.setDisable(false);
            if (!versionComboBox.getItems().isEmpty()) {
                versionComboBox.getSelectionModel().selectFirst();
                statusLabel.setText("已加载 " + versionComboBox.getItems().size() + " 个版本");
                progressBar.setProgress(0);
                installButton.setDisable(false);
            } else {
                statusLabel.setText("官方版本列表为空");
                progressBar.setProgress(0);
            }
            updateLaunchButton();
        });
        task.setOnFailed(_ -> {
            statusLabel.setText("加载版本列表失败：" + messageFor(task.getException()));
            progressBar.setProgress(0);
            mirrorComboBox.setDisable(false);
            versionComboBox.setDisable(false);
        });
        new Thread(task, "minecraft-version-list").start();
    }

    @FXML
    private void onAddAccountClick() {
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("添加离线账号");
        dialog.setHeaderText("输入游戏内显示的玩家名称");
        dialog.setContentText("名称（3–16 位英文字母、数字或下划线）：");
        Optional<String> result = dialog.showAndWait();
        if (result.isEmpty()) {
            return;
        }
        String name = result.get().trim();
        if (!name.matches("[A-Za-z0-9_]{3,16}")) {
            showError("账号名称无效", "离线账号名必须为 3 到 16 位英文字母、数字或下划线。");
            return;
        }
        try {
            String id = java.util.UUID.randomUUID().toString();
            accountStore.add(new AccountStore.Account(id, name, AccountType.OFFLINE, ""),
                    offlineSession(name));
            refreshAccounts();
            statusLabel.setText("已添加离线账号 " + name);
        } catch (UncheckedIOException exception) {
            statusLabel.setText("保存账号失败：" + messageFor(exception));
        }
    }

    @FXML
    private void onMicrosoftLoginClick() {
        String clientId = microsoftClientIdField.getText() == null ? "" : microsoftClientIdField.getText().trim();
        if (clientId.isEmpty()) {
            showError("需要 Microsoft OAuth 客户端 ID",
                    "请填写已注册为公共客户端并启用设备代码流的 Microsoft OAuth 客户端 ID。");
            microsoftClientIdField.requestFocus();
            return;
        }
        try {
            saveConfig("microsoft-client-id", clientId);
        } catch (UncheckedIOException exception) {
            showError("保存设置失败", messageFor(exception));
            return;
        }
        authenticateAccount(AccountType.MICROSOFT, "", "", null, clientId);
    }

    @FXML
    private void onYggdrasilLoginClick() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("添加第三方账号");
        dialog.setHeaderText("登录 Authlib-Injector 兼容的认证服务器");
        ButtonType loginButton = new ButtonType("登录", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().setAll(loginButton, ButtonType.CANCEL);
        TextField serverField = new TextField();
        serverField.setPromptText("https://auth.example.com/");
        TextField usernameField = new TextField();
        usernameField.setPromptText("邮箱或账号");
        PasswordField passwordField = new PasswordField();
        passwordField.setPromptText("密码");
        GridPane fields = new GridPane();
        fields.setHgap(10);
        fields.setVgap(10);
        fields.setPadding(new Insets(10, 0, 0, 0));
        fields.add(new Label("认证服务器"), 0, 0);
        fields.add(serverField, 1, 0);
        fields.add(new Label("账号"), 0, 1);
        fields.add(usernameField, 1, 1);
        fields.add(new Label("密码"), 0, 2);
        fields.add(passwordField, 1, 2);
        dialog.getDialogPane().setContent(fields);
        Optional<ButtonType> result = dialog.showAndWait();
        if (result.isEmpty() || result.get() != loginButton) {
            passwordField.clear();
            return;
        }
        String server = serverField.getText() == null ? "" : serverField.getText().trim();
        String username = usernameField.getText() == null ? "" : usernameField.getText().trim();
        char[] password = passwordField.getText().toCharArray();
        passwordField.clear();
        if (server.isEmpty() || username.isEmpty() || password.length == 0) {
            java.util.Arrays.fill(password, '\0');
            showError("信息不完整", "请填写认证服务器地址、账号和密码。");
            return;
        }
        authenticateAccount(AccountType.YGGDRASIL, server, username, password, "");
    }

    private void authenticateAccount(AccountType type, String server, String username,
                                     char[] password, String clientId) {
        microsoftLoginButton.setDisable(true);
        statusLabel.setText(type == AccountType.MICROSOFT
                ? "正在等待 Microsoft 账号验证..." : "正在登录第三方认证服务器...");
        progressBar.setProgress(-1);
        Task<MinecraftSession> task = new Task<>() {
            @Override
            protected MinecraftSession call() throws Exception {
                try {
                    if (type == AccountType.MICROSOFT) {
                        return accountAuthenticator.authenticateMicrosoft(clientId, deviceCode ->
                                Platform.runLater(() -> {
                                    statusLabel.setText("请在浏览器中确认 Microsoft 登录验证码");
                                    boolean opened = AccountAuthenticator.openBrowser(deviceCode.verificationUri());
                                    Alert instructions = new Alert(Alert.AlertType.INFORMATION);
                                    instructions.setTitle("Microsoft 正版账号登录");
                                    instructions.setHeaderText("请完成 Microsoft 账号验证");
                                    instructions.setContentText((opened
                                            ? "已打开浏览器。若未打开，请访问：\n" + deviceCode.verificationUri()
                                            : "请访问以下页面：\n" + deviceCode.verificationUri())
                                            + "\n\n输入验证码： " + deviceCode.userCode());
                                    instructions.showAndWait();
                                }));
                    }
                    return accountAuthenticator.authenticateYggdrasil(server, username, password);
                } finally {
                    if (password != null) {
                        java.util.Arrays.fill(password, '\0');
                    }
                }
            }
        };
        task.setOnSucceeded(_ -> {
            MinecraftSession session = task.getValue();
            try {
                String accountId = java.util.UUID.randomUUID().toString();
                accountStore.add(new AccountStore.Account(accountId, session.getPlayerName(), type, server), session);
                refreshAccounts();
                statusLabel.setText("已登录 " + type.getDisplayName() + "账号 " + session.getPlayerName());
            } catch (UncheckedIOException exception) {
                statusLabel.setText("保存账号失败：" + messageFor(exception));
            } finally {
                microsoftLoginButton.setDisable(false);
                progressBar.setProgress(0);
            }
        });
        task.setOnFailed(_ -> {
            statusLabel.setText("登录失败：" + messageFor(task.getException()));
            microsoftLoginButton.setDisable(false);
            progressBar.setProgress(0);
        });
        Thread loginThread = new Thread(task, "account-login-" + type.name().toLowerCase());
        loginThread.setDaemon(true);
        loginThread.start();
    }

    @FXML
    private void onManageAccountsClick() {
        AccountStore.Account selected = accountListView.getSelectionModel().getSelectedItem();
        if (selected == null) {
            statusLabel.setText("请先选择要移除的账号");
            return;
        }
        Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION);
        confirmation.setTitle("移除账号");
        confirmation.setHeaderText("确定移除账号 " + selected.getName() + "？");
        confirmation.setContentText("此操作只会从启动器中移除该账号及本次运行中的登录状态。");
        Optional<javafx.scene.control.ButtonType> result = confirmation.showAndWait();
        if (result.isEmpty() || result.get() != javafx.scene.control.ButtonType.OK) {
            return;
        }
        try {
            accountStore.remove(selected.getId());
            refreshAccounts();
            refreshAccountList();
            statusLabel.setText("已移除账号 " + selected.getName());
        } catch (UncheckedIOException exception) {
            statusLabel.setText("删除账号失败：" + messageFor(exception));
        }
    }

    @FXML
    private void onRefreshInstalledProfilesClick() {
        refreshInstalledProfiles(null);
    }

    @FXML
    private void onCreateRoomClick() {
        String player = selectedPlayerName();
        if (player == null) {
            roomStateLabel.setText("请先在左下角添加并选择账号。");
            return;
        }
        statusLabel.setText("正在启动陶瓦联机并创建房间...");
        createRoomButton.setDisable(true);
        terracottaService.createRoom(player).whenComplete((ignored, error) -> Platform.runLater(() -> {
            createRoomButton.setDisable(false);
            progressBar.setProgress(0);
            if (error != null) {
                statusLabel.setText("创建房间失败：" + messageFor(unwrapCompletionException(error)));
            } else {
                statusLabel.setText("已请求创建房间。请在 Minecraft 中进入存档并“对局域网开放”。");
            }
        }));
    }

    @FXML
    private void onJoinRoomClick() {
        String player = selectedPlayerName();
        String room = roomCodeField.getText() == null ? "" : roomCodeField.getText().trim().toUpperCase();
        if (player == null) {
            roomStateLabel.setText("请先在左下角添加并选择账号。");
            return;
        }
        if (!room.matches("U/[0-9A-HJ-NP-Z]{4}-[0-9A-HJ-NP-Z]{4}-[0-9A-HJ-NP-Z]{4}-[0-9A-HJ-NP-Z]{4}")) {
            roomStateLabel.setText("房间码格式不正确，例如 U/ABCD-EFGH-JKLM-NPQR。");
            return;
        }
        statusLabel.setText("正在启动陶瓦联机并加入房间...");
        joinRoomButton.setDisable(true);
        terracottaService.joinRoom(room, player).whenComplete((ignored, error) -> Platform.runLater(() -> {
            joinRoomButton.setDisable(false);
            progressBar.setProgress(0);
            if (error != null) {
                statusLabel.setText("加入房间失败：" + messageFor(unwrapCompletionException(error)));
            } else {
                statusLabel.setText("已提交加入请求，正在连接房间...");
            }
        }));
    }

    @FXML
    private void onLeaveRoomClick() {
        roomStateLabel.setText("正在断开联机...");
        terracottaService.returnToWaiting().whenComplete((ignored, error) -> Platform.runLater(() -> {
            if (error != null) {
                roomStateLabel.setText("断开联机失败：" + messageFor(unwrapCompletionException(error)));
            } else {
                roomStateLabel.setText("已断开联机。");
                roomCodeLabel.setText("等待创建或加入房间");
                roomMembersLabel.setText("尚无玩家");
            }
        }));
    }

    @FXML
    private void onLaunchFromMultiplayerClick() {
        showPlayPage();
        onLaunchClick();
    }

    @FXML
    private void onDeleteInstalledProfileClick() {
        InstalledProfile profile = profileListView.getSelectionModel().getSelectedItem();
        if (profile == null) {
            statusLabel.setText("请先选择要删除的版本");
            return;
        }

        Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION);
        confirmation.setTitle("删除已安装版本");
        confirmation.setHeaderText("确定删除 " + profile.id + "？");
        confirmation.setContentText(profile.isolated
                ? "将删除整个独立实例目录，包括此实例中的存档、模组和设置；其他版本不受影响。"
                : "只删除该版本目录；共享库、资源文件、存档和其他版本不会删除。");
        Optional<ButtonType> result = confirmation.showAndWait();
        if (result.isEmpty() || result.get() != ButtonType.OK) {
            return;
        }

        Path profileDirectory;
        if (profile.isolated) {
            Path instancesDirectory = getInstancesDirectory();
            if (!instancesDirectory.equals(profile.gameDirectory.getParent())) {
                statusLabel.setText("无法删除该独立实例：实例目录无效");
                return;
            }
            profileDirectory = profile.gameDirectory;
        } else {
            Path versionsDirectory = profile.gameDirectory.resolve("versions").toAbsolutePath().normalize();
            profileDirectory = versionsDirectory.resolve(profile.id).normalize();
            if (!versionsDirectory.equals(profileDirectory.getParent())) {
                statusLabel.setText("无法删除该版本：版本目录无效");
                return;
            }
        }
        if (!Files.exists(profileDirectory, LinkOption.NOFOLLOW_LINKS)) {
            statusLabel.setText("该版本目录已不存在");
            refreshInstalledProfiles(null);
            return;
        }

        try {
            deleteProfileDirectory(profileDirectory);
            statusLabel.setText(profile.isolated
                    ? "已删除独立实例 " + profile.id + "（包括该实例内的存档和设置）"
                    : "已删除版本 " + profile.id);
            refreshInstalledProfiles(null);
        } catch (IOException exception) {
            statusLabel.setText("删除版本失败：" + messageFor(exception));
        }
    }

    @FXML
    private void onAccountSelectedClick() {
        AccountStore.Account selected = accountListView.getSelectionModel().getSelectedItem();
        if (selected == null) {
            statusLabel.setText("请先选择一个账号");
            return;
        }
        try {
            accountStore.select(selected.getId());
            refreshAccounts();
            statusLabel.setText("已将 " + selected.getName() + " 设为当前账号");
        } catch (UncheckedIOException exception) {
            statusLabel.setText("保存账号设置失败：" + messageFor(exception));
        }
    }

    @FXML
    private void onInstallClick() {
        MinecraftInstaller.VersionEntry version = versionComboBox.getValue();
        if (version == null) {
            statusLabel.setText("请先选择 Minecraft 版本");
            return;
        }

        MirrorSource selectedMirror = mirrorComboBox.getValue();
        MinecraftInstaller selectedInstaller = new MinecraftInstaller(selectedMirror);
        Path gameDirectory;
        try {
            gameDirectory = getInstallGameDirectory();
        } catch (IllegalArgumentException exception) {
            statusLabel.setText("实例名称无效：" + messageFor(exception));
            return;
        }
        boolean isolated = isolateVersionCheckBox.isSelected();
        String instanceName = instanceNameField.getText().trim();
        setBusy(true);
        progressBar.setProgress(-1);
        statusLabel.setText("正在准备安装 " + version.getId() + "...");

        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                selectedInstaller.install(version, gameDirectory, (completed, total, name) ->
                        Platform.runLater(() -> {
                            progressBar.setProgress(total == 0 ? 0 : (double) completed / total);
                            statusLabel.setText("正在下载 " + name + " (" + completed + "/" + total + ")");
                        }));
                if (isolated) {
                    writeInstanceMetadata(gameDirectory, instanceName, version.getId());
                }
                return null;
            }
        };
        task.setOnSucceeded(_ -> {
            statusLabel.setText(version.getId() + " 安装完成");
            progressBar.setProgress(1);
            setBusy(false);
            refreshInstalledProfiles(null);
            selectInstalledProfile(gameDirectory, version.getId());
            updateLaunchButton();
        });
        task.setOnFailed(_ -> {
            statusLabel.setText("安装失败：" + messageFor(task.getException()));
            progressBar.setProgress(0);
            setBusy(false);
            updateLaunchButton();
        });
        new Thread(task, "minecraft-install-" + version.getId()).start();
    }

    @FXML
    private void onLaunchClick() {
        AccountStore.Account account = accountComboBox.getValue();
        if (account == null) {
            statusLabel.setText("请先添加并选择账号");
            return;
        }
        MinecraftSession session = accountStore.getSession(account.getId());
        if (account.getType() == AccountType.OFFLINE) {
            session = offlineSession(account.getName());
        } else if (session == null) {
            statusLabel.setText("账号登录状态已失效，请重新登录 " + account.getName());
            showAccountsPage();
            return;
        }
        try {
            InstalledProfile profile = profileListView.getSelectionModel().getSelectedItem();
            if (profile == null) {
                statusLabel.setText("所选 Minecraft 版本尚未安装");
                return;
            }
            int requiredJava = gameLauncher.getRequiredJavaFeature(profile.id, profile.gameDirectory);
            String prefix = profileSettingsPrefix(profile);
            int maxMemory = configuredMemory(prefix + "memory-gb");
            String profileJava = LauncherData.get(prefix + "java", "");
            boolean automaticJava = "auto".equals(profileJava);
            String configuredJava = automaticJava ? "" : profileJava;
            if (configuredJava.isBlank() && !automaticJava) {
                configuredJava = LauncherData.get("java-path", "");
            }
            JavaRuntimeManager.JavaInstallation selectedJava;
            if (!configuredJava.isBlank()) {
                Path executable = Paths.get(configuredJava).toAbsolutePath().normalize();
                selectedJava = javaInstallations.get(executable);
                if (selectedJava == null) {
                    selectedJava = JavaRuntimeManager.inspectExecutable(executable);
                }
                if (selectedJava.getMajorVersion() < requiredJava) {
                    throw new IOException("Minecraft " + profile.id + " 需要 Java " + requiredJava
                            + " 或更高版本；当前所选 Java 为 " + selectedJava.getMajorVersion());
                }
            } else {
                JavaRuntimeManager.JavaInstallation compatible =
                        JavaRuntimeManager.findCompatible(new ArrayList<>(javaInstallations.values()), requiredJava);
                if (compatible == null && javaInstallations.isEmpty()) {
                    List<JavaRuntimeManager.JavaInstallation> detected = JavaRuntimeManager.detectInstalled();
                    for (JavaRuntimeManager.JavaInstallation installation : detected) {
                        javaInstallations.put(installation.getExecutablePath(), installation);
                    }
                    compatible = JavaRuntimeManager.findCompatible(detected, requiredJava);
                }
                if (compatible == null) {
                    throw new IOException("未检测到适用于 Minecraft " + profile.id + " 的 Java "
                            + requiredJava + "。请下载并安装兼容版本后点击“重新检测 Java”。");
                }
                selectedJava = compatible;
            }
            String arguments = LauncherData.get(prefix + "jvm-arguments",
                    LauncherData.get("jvm-arguments", ""));
            gameProcess = gameLauncher.launch(profile.id, session, profile.gameDirectory, maxMemory,
                    selectedJava.getExecutablePath(), arguments);
            gameProcess.onExit().thenAccept(process -> Platform.runLater(() -> {
                if (gameProcess == process) {
                    statusLabel.setText(process.exitValue() == 0
                            ? "Minecraft 已退出" : "Minecraft 异常退出，日志：.minecraft/logs/starflux-launcher.log");
                    gameProcess = null;
                    updateLaunchButton();
                    updateInstalledProfileActions();
                }
            }));
            statusLabel.setText("Minecraft " + profile.id + " 已启动（账号：" + account.getName() + "）");
            updateLaunchButton();
            updateInstalledProfileActions();
        } catch (Exception exception) {
            statusLabel.setText("启动失败：" + messageFor(exception));
        }
    }

    private int configuredMemory(String profileKey) throws IOException {
        String configured = LauncherData.get(profileKey, "").trim();
        if (configured.isEmpty()) {
            return selectedMemoryGb();
        }
        try {
            int value = Integer.parseInt(configured);
            if (value >= 1 && value <= 64) {
                return value;
            }
        } catch (NumberFormatException exception) {
            throw new IOException("该版本的最大内存配置无效：" + configured, exception);
        }
        throw new IOException("该版本最大内存必须在 1–64 GB 之间");
    }

    private void refreshAccounts() {
        String selectedId = accountStore.getSelectedId();
        List<AccountStore.Account> accounts = accountStore.getAccounts();
        accountComboBox.getItems().setAll(accounts);
        AccountStore.Account selected = accounts.stream()
                .filter(account -> account.getId().equals(selectedId)).findFirst().orElse(null);
        if (selected == null) {
            if (!accountComboBox.getItems().isEmpty()) {
                accountComboBox.getSelectionModel().selectFirst();
            } else {
                accountComboBox.getSelectionModel().clearSelection();
            }
        } else {
            accountComboBox.getSelectionModel().select(selected);
        }
        refreshAccountList();
        updateAccountEntry();
        updateLaunchButton();
    }

    private void refreshAccountList() {
        String selectedId = accountStore.getSelectedId();
        List<AccountStore.Account> accounts = accountStore.getAccounts();
        accountListView.getItems().setAll(accounts);
        AccountStore.Account selected = accounts.stream()
                .filter(account -> account.getId().equals(selectedId)).findFirst().orElse(null);
        if (selected != null) {
            accountListView.getSelectionModel().select(selected);
        } else {
            accountListView.getSelectionModel().clearSelection();
        }
    }

    private void updateAccountEntry() {
        if (accountEntryNameLabel == null) {
            return;
        }
        AccountStore.Account account = accountComboBox.getValue();
        if (account == null) {
            accountEntryNameLabel.setText("未登录");
            accountEntryTypeLabel.setText("点击添加账号");
        } else {
            accountEntryNameLabel.setText(account.getName());
            accountEntryTypeLabel.setText(account.getType().getDisplayName());
        }
    }

    private String selectedPlayerName() {
        AccountStore.Account account = accountComboBox.getValue();
        return account == null ? null : account.getName();
    }

    private static MinecraftSession offlineSession(String playerName) {
        String uuid = java.util.UUID.nameUUIDFromBytes(
                ("OfflinePlayer:" + playerName).getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .toString().replace("-", "");
        return new MinecraftSession(playerName, uuid, "0", "legacy", "0", "0", "{}");
    }

    private void updateLaunchButton() {
        if (launchButton == null) {
            return;
        }
        AccountStore.Account account = accountComboBox.getValue();
        InstalledProfile profile = profileListView.getSelectionModel().getSelectedItem();
        boolean installed = profile != null && account != null;
        launchButton.setDisable(!installed || gameProcess != null);
        launchButton.setText(installed ? "启动游戏" : "安装后即可启动");
    }

    private void updateInstalledProfileActions() {
        InstalledProfile selectedProfile = profileListView.getSelectionModel().getSelectedItem();
        if (configureProfileButton != null) {
            configureProfileButton.setDisable(selectedProfile == null);
        }
        if (renameInstanceButton != null) {
            renameInstanceButton.setDisable(selectedProfile == null || !selectedProfile.isolated
                    || profileListView.isDisabled() || gameProcess != null);
        }
        if (deleteProfileButton != null) {
            deleteProfileButton.setDisable(selectedProfile == null
                    || profileListView.isDisabled() || gameProcess != null);
            deleteProfileButton.setText(selectedProfile != null && selectedProfile.isolated
                    ? "删除实例" : "删除版本");
        }
    }

    private void setBusy(boolean busy) {
        mirrorComboBox.setDisable(busy);
        versionComboBox.setDisable(busy);
        isolateVersionCheckBox.setDisable(busy);
        instanceNameField.setDisable(busy);
        profileListView.setDisable(busy);
        installButton.setDisable(busy || versionComboBox.getValue() == null);
        launchButton.setDisable(busy);
        updateInstalledProfileActions();
    }

    private static void deleteProfileDirectory(Path profileDirectory) throws IOException {
        Files.walkFileTree(profileDirectory, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException exception) throws IOException {
                if (exception != null) {
                    throw exception;
                }
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void refreshInstalledProfiles(String preferredType) {
        InstalledProfile oldSelection = profileListView.getSelectionModel().getSelectedItem();
        List<InstalledProfile> profiles = new ArrayList<InstalledProfile>();
        Path gameDirectory = getGameDirectory();
        scanInstalledProfiles(gameDirectory, false, profiles);
        Path instancesDirectory = getInstancesDirectory();
        if (Files.isDirectory(instancesDirectory)) {
            try (Stream<Path> instances = Files.list(instancesDirectory)) {
                instances.filter(Files::isDirectory)
                        .forEach(instance -> scanInstalledProfiles(instance, true, profiles));
            } catch (IOException exception) {
                statusLabel.setText("读取独立实例失败：" + messageFor(exception));
            }
        }
        profiles.sort(java.util.Comparator.comparing(InstalledProfile::toString,
                String.CASE_INSENSITIVE_ORDER));
        profileListView.getItems().setAll(profiles);
        installedProfilesLabel.setText(profiles.size() + " 个可启动配置");
        InstalledProfile selection = findProfile(profiles, oldSelection);
        if (selection == null && preferredType != null) {
            for (InstalledProfile profile : profiles) {
                if (profile.id.toLowerCase().contains(preferredType)
                        || profile.type.toLowerCase().contains(preferredType)) {
                    selection = profile;
                    break;
                }
            }
        }
        if (selection == null && !profiles.isEmpty()) {
            selection = profiles.get(0);
        }
        profileListView.getSelectionModel().select(selection);
        updateLaunchButton();
    }

    private void scanInstalledProfiles(Path gameDirectory, boolean isolated, List<InstalledProfile> profiles) {
        Path versionsDirectory = gameDirectory.resolve("versions");
        if (!Files.isDirectory(versionsDirectory)) {
            return;
        }
        String instanceName = isolated ? readInstanceName(gameDirectory) : "";
        try (Stream<Path> directories = Files.list(versionsDirectory)) {
            java.util.Iterator<Path> iterator = directories.iterator();
            while (iterator.hasNext()) {
                Path directory = iterator.next();
                String id = directory.getFileName().toString();
                if (!Files.isDirectory(directory)) {
                    continue;
                }
                Path profileFile = directory.resolve(id + ".json");
                if (!Files.isRegularFile(profileFile)) {
                    continue;
                }
                JsonObject json;
                try {
                    json = new JsonParser().parse(new String(Files.readAllBytes(profileFile), "UTF-8"))
                            .getAsJsonObject();
                } catch (RuntimeException exception) {
                    continue;
                } catch (IOException exception) {
                    statusLabel.setText("读取游戏配置失败：" + messageFor(exception));
                    continue;
                }
                String parent = getJsonString(json, "inheritsFrom");
                if (parent == null && Files.isRegularFile(directory.resolve(id + ".jar"))) {
                    profiles.add(new InstalledProfile(id, "原版", gameDirectory, isolated, instanceName));
                } else if (parent != null && parent.matches("[A-Za-z0-9._-]+")
                        && Files.isRegularFile(versionsDirectory.resolve(parent)
                        .resolve(parent + ".jar"))) {
                    profiles.add(new InstalledProfile(id, loaderName(id), gameDirectory, isolated, instanceName));
                }
            }
        } catch (IOException exception) {
            statusLabel.setText("读取已安装版本失败：" + messageFor(exception));
        }
    }

    private static InstalledProfile findProfile(List<InstalledProfile> profiles, InstalledProfile target) {
        if (target == null) {
            return null;
        }
        for (InstalledProfile profile : profiles) {
            if (profile.id.equals(target.id) && profile.gameDirectory.equals(target.gameDirectory)) {
                return profile;
            }
        }
        return null;
    }

    private void selectInstalledProfile(Path gameDirectory, String identifier) {
        for (InstalledProfile profile : profileListView.getItems()) {
            if (profile.gameDirectory.equals(gameDirectory)
                    && (profile.id.equals(identifier) || profile.type.equalsIgnoreCase(identifier))) {
                profileListView.getSelectionModel().select(profile);
                return;
            }
        }
    }

    private static String getJsonString(JsonObject json, String key) {
        return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : null;
    }

    private static String loaderName(String id) {
        String lower = id.toLowerCase();
        if (lower.contains("neoforge")) {
            return "NeoForge";
        }
        if (lower.contains("forge")) {
            return "Forge";
        }
        if (lower.contains("quilt")) {
            return "Quilt";
        }
        if (lower.contains("fabric")) {
            return "Fabric";
        }
        return "模组加载器";
    }

    public StackPane getPageContainer() {
        return pageContainer;
    }

    private static final class InstalledProfile {
        private final String id;
        private final String type;
        private final Path gameDirectory;
        private final boolean isolated;
        private final String instanceName;

        private InstalledProfile(String id, String type, Path gameDirectory, boolean isolated, String instanceName) {
            this.id = id;
            this.type = type;
            this.gameDirectory = gameDirectory;
            this.isolated = isolated;
            this.instanceName = instanceName;
        }

        @Override
        public String toString() {
            return (isolated ? instanceName + " · " : "")
                    + (id.equals(type) ? id : type + " · " + id);
        }
    }

    private Path getGameDirectory() {
        String configured = LauncherData.get(GAME_DIRECTORY_KEY, "");
        if (!configured.trim().isEmpty()) {
            return Paths.get(configured).toAbsolutePath().normalize();
        }
        return Paths.get(System.getProperty("user.home"), ".minecraft").toAbsolutePath().normalize();
    }

    private Path getInstancesDirectory() {
        return getGameDirectory().resolve("instances").toAbsolutePath().normalize();
    }

    private Path getInstallGameDirectory() {
        if (isolateVersionCheckBox.isSelected()) {
            String name = instanceNameField.getText() == null ? "" : instanceNameField.getText().trim();
            validateInstanceName(name);
            String folderName = name.replaceAll("[^A-Za-z0-9._-]+", "-")
                    .replaceAll("^[.-]+|[.-]+$", "");
            if (folderName.isEmpty()) {
                folderName = "instance";
            }
            if (folderName.length() > 48) {
                folderName = folderName.substring(0, 48);
            }
            String suffix = java.util.UUID.nameUUIDFromBytes(
                    name.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString().substring(0, 8);
            Path instancesDirectory = getInstancesDirectory();
            Path instanceDirectory = instancesDirectory.resolve("instance-" + folderName + "-" + suffix)
                    .toAbsolutePath().normalize();
            if (!instancesDirectory.equals(instanceDirectory.getParent())) {
                throw new IllegalArgumentException("实例名称无法用作独立目录");
            }
            return instanceDirectory;
        }
        return getGameDirectory();
    }

    private static void validateInstanceName(String name) {
        if (name == null || !name.matches("[\\p{L}\\p{N}][\\p{L}\\p{N} ._-]{0,47}")
                || name.endsWith(".") || name.endsWith(" ")) {
            throw new IllegalArgumentException("名称须以字母或数字开头，最多 48 个字符，且不能以空格或句点结尾");
        }
    }

    private static void writeInstanceMetadata(Path gameDirectory, String name, String minecraftVersion)
            throws IOException {
        validateInstanceName(name);
        JsonObject metadata = new JsonObject();
        metadata.addProperty("name", name);
        metadata.addProperty("minecraftVersion", minecraftVersion);
        Path metadataFile = gameDirectory.resolve("starflux-instance.json");
        Path temporary = metadataFile.resolveSibling(metadataFile.getFileName() + ".tmp");
        Files.writeString(temporary, metadata.toString(), java.nio.charset.StandardCharsets.UTF_8);
        try {
            Files.move(temporary, metadataFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
            Files.move(temporary, metadataFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private String minecraftVersionForProfile(InstalledProfile profile) throws IOException {
        Path versionFile = profile.gameDirectory.resolve("versions").resolve(profile.id)
                .resolve(profile.id + ".json");
        try {
            JsonObject version = new JsonParser().parse(
                    Files.readString(versionFile, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            String parent = getJsonString(version, "inheritsFrom");
            return parent == null ? profile.id : parent;
        } catch (IOException | RuntimeException exception) {
            throw new IOException("无法读取实例的 Minecraft 版本", exception);
        }
    }

    private String readInstanceName(Path gameDirectory) {
        Path metadataFile = gameDirectory.resolve("starflux-instance.json");
        if (!Files.isRegularFile(metadataFile)) {
            return gameDirectory.getFileName() == null ? gameDirectory.toString()
                    : gameDirectory.getFileName().toString();
        }
        try {
            JsonObject metadata = new JsonParser().parse(
                    Files.readString(metadataFile, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            String name = getJsonString(metadata, "name");
            return name == null || name.isBlank()
                    ? gameDirectory.getFileName().toString() : name;
        } catch (IOException | RuntimeException exception) {
            statusLabel.setText("读取实例名称失败：" + messageFor(exception));
            return gameDirectory.getFileName() == null ? gameDirectory.toString()
                    : gameDirectory.getFileName().toString();
        }
    }

    private void saveConfig(String key, String value) {
        try {
            LauncherData.put(key, value);
        } catch (UncheckedIOException exception) {
            statusLabel.setText("保存启动器配置失败：" + messageFor(exception));
        }
    }

    private void updateTerracottaState(TerracottaService.RoomState state) {
        if (roomStateLabel == null) {
            return;
        }
        switch (state.state) {
            case "waiting":
                roomStateLabel.setText("陶瓦联机已就绪，创建房间或输入邀请码加入。");
                roomCodeLabel.setText("等待创建或加入房间");
                roomMembersLabel.setText("尚无玩家");
                break;
            case "host-scanning":
                roomStateLabel.setText("正在扫描本机 Minecraft 局域网房间。请进入世界并点击“对局域网开放”。");
                break;
            case "host-starting":
                roomStateLabel.setText("已发现本机世界，正在建立联机网络...");
                if (!state.room.isEmpty()) {
                    roomCodeLabel.setText(state.room);
                }
                break;
            case "host-ok":
                roomStateLabel.setText("房间已创建，分享邀请码给朋友。");
                roomCodeLabel.setText(state.room);
                roomMembersLabel.setText(state.profiles.isEmpty()
                        ? "等待玩家加入" : String.join("  ·  ", state.profiles));
                break;
            case "guest-connecting":
            case "guest-starting":
                roomStateLabel.setText("正在连接房间" + (state.room.isEmpty() ? "..." : " " + state.room + "…"));
                if (!state.room.isEmpty()) {
                    roomCodeLabel.setText(state.room);
                }
                break;
            case "guest-ok":
                roomStateLabel.setText("已成功加入联机网络。启动游戏后，在多人游戏列表中加入“陶瓦联机大厅”。");
                roomCodeLabel.setText("备用地址  " + state.url);
                roomMembersLabel.setText(state.profiles.isEmpty()
                        ? "已连接" : String.join("  ·  ", state.profiles));
                break;
            case "exception":
                roomStateLabel.setText("联机连接异常：" + state.message + "。可尝试断开后重新加入。");
                break;
            case "unavailable":
                roomStateLabel.setText("陶瓦联机状态暂不可用：" + state.message);
                break;
            default:
                roomStateLabel.setText("陶瓦联机状态：" + state.state);
                break;
        }
        leaveRoomButton.setDisable("waiting".equals(state.state) || "unavailable".equals(state.state));
    }

    private static Throwable unwrapCompletionException(Throwable error) {
        return error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
    }

    private void updateGameDirectoryLabel() {
        if (gameDirectoryLabel != null) {
            gameDirectoryLabel.setText("游戏目录  " + getGameDirectory());
        }
    }

    private static void showError(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

    private static String messageFor(Throwable error) {
        if (error == null || error.getMessage() == null || error.getMessage().trim().isEmpty()) {
            return "未知错误";
        }
        return error.getMessage();
    }
}
