package com.fumofu.starflux.app;

import com.fumofu.starflux.account.OfflineAccountStore;
import com.fumofu.starflux.config.LauncherData;
import com.fumofu.starflux.download.MinecraftInstaller;
import com.fumofu.starflux.download.MirrorSource;
import com.fumofu.starflux.game.LoaderType;
import com.fumofu.starflux.game.MinecraftGameLauncher;
import com.fumofu.starflux.multiplayer.TerracottaService;
import com.fumofu.starflux.ui.LoaderInstallDialog;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Slider;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.TextField;
import javafx.scene.layout.StackPane;
import javafx.stage.DirectoryChooser;
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
import java.util.List;
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
    private ComboBox<String> accountComboBox;
    @FXML
    private ListView<InstalledProfile> profileListView;
    @FXML
    private Label installedProfilesLabel;
    @FXML
    private ListView<String> accountListView;
    @FXML
    private Button installButton;
    @FXML
    private Button launchButton;
    @FXML
    private Button deleteProfileButton;
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
    private Label systemMemoryLabel;
    @FXML
    private javafx.scene.control.CheckBox isolateVersionCheckBox;

    private final OfflineAccountStore accountStore = new OfflineAccountStore();
    private final MinecraftGameLauncher gameLauncher = new MinecraftGameLauncher();
    private final TerracottaService terracottaService = new TerracottaService();
    private static final String GAME_DIRECTORY_KEY = "game-directory";
    private Process gameProcess;

    public void close() {
        terracottaService.close();
    }

    @FXML
    private void initialize() {
        gameDirectoryField.setText(getGameDirectory().toAbsolutePath().toString());
        updateGameDirectoryLabel();

        mirrorComboBox.getItems().setAll(MirrorSource.values());
        initializeMemoryControls();
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
                    accountStore.selectAccount(newValue);
                } catch (UncheckedIOException exception) {
                    statusLabel.setText("保存账号设置失败：" + messageFor(exception));
                }
            }
            if (accountListView != null) {
                refreshAccountList();
            }
            updateLaunchButton();
        });
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
                roomStateLabel.setText(message);
            }
        }));
        terracottaService.startPolling();
        showPlayPage();
        loadVersions(mirrorComboBox.getValue());
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
        activeButton.getStyleClass().setAll("nav-button", "nav-button-active");
    }

    @FXML
    private void onLoaderMenuClick() {
        MinecraftInstaller.VersionEntry minecraftVersion = versionComboBox.getValue();
        if (minecraftVersion == null) {
            statusLabel.setText("请先选择 Minecraft 版本");
            return;
        }

        Optional<LoaderType> installed = new LoaderInstallDialog().showAndWait(
                launchButton.getScene().getWindow(), minecraftVersion.getId(),
                getInstallGameDirectory(minecraftVersion.getId()));
        if (installed.isPresent()) {
            refreshInstalledProfiles(installed.get().name().toLowerCase());
            statusLabel.setText(installed.get() + " 安装完成");
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
            accountStore.addAccount(name);
            refreshAccounts();
            statusLabel.setText("已添加离线账号 " + name);
        } catch (UncheckedIOException exception) {
            statusLabel.setText("保存账号失败：" + messageFor(exception));
        }
    }

    @FXML
    private void onManageAccountsClick() {
        String selected = accountListView.getSelectionModel().getSelectedItem();
        if (selected == null) {
            statusLabel.setText("请先在设置页选择要删除的账号");
            return;
        }
        Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION);
        confirmation.setTitle("删除离线账号");
        confirmation.setHeaderText("确定删除账号 " + selected + "？");
        confirmation.setContentText("此操作只会从启动器中移除该账号。");
        Optional<javafx.scene.control.ButtonType> result = confirmation.showAndWait();
        if (result.isEmpty() || result.get() != javafx.scene.control.ButtonType.OK) {
            return;
        }
        try {
            accountStore.removeAccount(selected);
            refreshAccounts();
            refreshAccountList();
            statusLabel.setText("已删除离线账号 " + selected);
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
        String player = accountComboBox.getValue();
        if (player == null) {
            roomStateLabel.setText("请先在设置中添加并选择离线账号。");
            return;
        }
        roomStateLabel.setText("正在启动陶瓦联机并创建房间；首次使用时会自动下载核心...");
        createRoomButton.setDisable(true);
        terracottaService.createRoom(player).whenComplete((ignored, error) -> Platform.runLater(() -> {
            createRoomButton.setDisable(false);
            progressBar.setProgress(0);
            if (error != null) {
                roomStateLabel.setText("创建房间失败：" + messageFor(unwrapCompletionException(error)));
            } else {
                roomStateLabel.setText("已请求创建房间。请在 Minecraft 中进入存档并“对局域网开放”。");
            }
        }));
    }

    @FXML
    private void onJoinRoomClick() {
        String player = accountComboBox.getValue();
        String room = roomCodeField.getText() == null ? "" : roomCodeField.getText().trim().toUpperCase();
        if (player == null) {
            roomStateLabel.setText("请先在设置中添加并选择离线账号。");
            return;
        }
        if (!room.matches("U/[0-9A-HJ-NP-Z]{4}-[0-9A-HJ-NP-Z]{4}-[0-9A-HJ-NP-Z]{4}-[0-9A-HJ-NP-Z]{4}")) {
            roomStateLabel.setText("房间码格式不正确，例如 U/ABCD-EFGH-JKLM-NPQR。");
            return;
        }
        roomStateLabel.setText("正在启动陶瓦联机并加入房间...");
        joinRoomButton.setDisable(true);
        terracottaService.joinRoom(room, player).whenComplete((ignored, error) -> Platform.runLater(() -> {
            joinRoomButton.setDisable(false);
            progressBar.setProgress(0);
            if (error != null) {
                roomStateLabel.setText("加入房间失败：" + messageFor(unwrapCompletionException(error)));
            } else {
                roomStateLabel.setText("已提交加入请求，正在连接房间...");
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
        String selected = accountListView.getSelectionModel().getSelectedItem();
        if (selected == null) {
            statusLabel.setText("请先选择一个离线账号");
            return;
        }
        try {
            accountStore.selectAccount(selected);
            refreshAccounts();
            statusLabel.setText("已将 " + selected + " 设为当前账号");
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
        Path gameDirectory = getInstallGameDirectory(version.getId());
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
                return null;
            }
        };
        task.setOnSucceeded(_ -> {
            statusLabel.setText(version.getId() + " 安装完成");
            progressBar.setProgress(1);
            setBusy(false);
            refreshInstalledProfiles(null);
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
        String account = accountComboBox.getValue();
        if (account == null) {
            statusLabel.setText("请先在设置中添加并选择离线账号");
            return;
        }
        try {
            InstalledProfile profile = profileListView.getSelectionModel().getSelectedItem();
            if (profile == null) {
                statusLabel.setText("所选 Minecraft 版本尚未安装");
                return;
            }
            int maxMemoryGb = (int) Math.round(memorySlider.getMax());
            commitMemoryField(maxMemoryGb);
            int maxMemory = (int) Math.round(memorySlider.getValue());
            gameProcess = gameLauncher.launch(profile.id, account, profile.gameDirectory, maxMemory);
            gameProcess.onExit().thenAccept(process -> Platform.runLater(() -> {
                if (gameProcess == process) {
                    statusLabel.setText(process.exitValue() == 0
                            ? "Minecraft 已退出" : "Minecraft 异常退出，日志：.minecraft/logs/starflux-launcher.log");
                    gameProcess = null;
                    updateLaunchButton();
                    updateInstalledProfileActions();
                }
            }));
            statusLabel.setText("Minecraft " + profile.id + " 已启动（离线账号：" + account + "）");
            updateLaunchButton();
            updateInstalledProfileActions();
        } catch (Exception exception) {
            statusLabel.setText("启动失败：" + messageFor(exception));
        }
    }

    private void refreshAccounts() {
        String selected = accountStore.getSelectedAccount();
        accountComboBox.getItems().setAll(accountStore.getAccounts());
        if (selected.isEmpty() || !accountComboBox.getItems().contains(selected)) {
            if (!accountComboBox.getItems().isEmpty()) {
                accountComboBox.getSelectionModel().selectFirst();
            } else {
                accountComboBox.getSelectionModel().clearSelection();
            }
        } else {
            accountComboBox.getSelectionModel().select(selected);
        }
        refreshAccountList();
        updateLaunchButton();
    }

    private void refreshAccountList() {
        String selected = accountStore.getSelectedAccount();
        accountListView.getItems().setAll(accountStore.getAccounts());
        if (selected != null && !selected.isEmpty() && accountListView.getItems().contains(selected)) {
            accountListView.getSelectionModel().select(selected);
        } else {
            accountListView.getSelectionModel().clearSelection();
        }
    }

    private void updateLaunchButton() {
        if (launchButton == null) {
            return;
        }
        String account = accountComboBox.getValue();
        InstalledProfile profile = profileListView.getSelectionModel().getSelectedItem();
        boolean installed = profile != null && account != null;
        launchButton.setDisable(!installed || gameProcess != null);
        launchButton.setText(installed ? "启动游戏" : "安装后即可启动");
    }

    private void updateInstalledProfileActions() {
        if (deleteProfileButton != null) {
            boolean noSelection = profileListView.getSelectionModel().getSelectedItem() == null;
            deleteProfileButton.setDisable(noSelection || profileListView.isDisabled() || gameProcess != null);
        }
    }

    private void setBusy(boolean busy) {
        mirrorComboBox.setDisable(busy);
        versionComboBox.setDisable(busy);
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
        profileListView.getItems().setAll(profiles);
        installedProfilesLabel.setText(profiles.size() + " 个版本");
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
                    profiles.add(new InstalledProfile(id, "原版", gameDirectory, isolated));
                } else if (parent != null && parent.matches("[A-Za-z0-9._-]+")
                        && Files.isRegularFile(versionsDirectory.resolve(parent)
                        .resolve(parent + ".jar"))) {
                    profiles.add(new InstalledProfile(id, loaderName(id), gameDirectory, isolated));
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

        private InstalledProfile(String id, String type, Path gameDirectory, boolean isolated) {
            this.id = id;
            this.type = type;
            this.gameDirectory = gameDirectory;
            this.isolated = isolated;
        }

        @Override
        public String toString() {
            return (isolated ? "独立 · " : "") + (id.equals(type) ? id : type + " · " + id);
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

    private Path getInstallGameDirectory(String versionId) {
        if (isolateVersionCheckBox.isSelected()) {
            return getInstancesDirectory().resolve(versionId).normalize();
        }
        return getGameDirectory();
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
