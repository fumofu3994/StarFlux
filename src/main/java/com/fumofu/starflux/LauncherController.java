package com.fumofu.starflux;

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
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.TextField;
import javafx.scene.layout.StackPane;
import javafx.stage.DirectoryChooser;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
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
import java.util.prefs.Preferences;
import java.util.stream.Stream;

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
    private Button playNavButton;
    @FXML
    private Button downloadNavButton;
    @FXML
    private Button settingsNavButton;
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

    private final OfflineAccountStore accountStore = new OfflineAccountStore();
    private final MinecraftGameLauncher gameLauncher = new MinecraftGameLauncher();
    private final Preferences preferences = Preferences.userNodeForPackage(LauncherController.class);
    private static final String GAME_DIRECTORY_KEY = "game-directory";
    private Process gameProcess;

    @FXML
    private void initialize() {
        gameDirectoryField.setText(getGameDirectory().toAbsolutePath().toString());
        updateGameDirectoryLabel();

        mirrorComboBox.getItems().setAll(MirrorSource.values());
        String savedMirror = preferences.get("download-mirror", MirrorSource.MOJANG.name());
        try {
            mirrorComboBox.getSelectionModel().select(MirrorSource.valueOf(savedMirror));
        } catch (IllegalArgumentException exception) {
            mirrorComboBox.getSelectionModel().select(MirrorSource.MOJANG);
        }
        mirrorComboBox.valueProperty().addListener((_, oldValue, newValue) -> {
            if (newValue != null && newValue != oldValue) {
                preferences.put("download-mirror", newValue.name());
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
                accountStore.selectAccount(newValue);
            }
            if (accountListView != null) {
                refreshAccountList();
            }
            updateLaunchButton();
        });
        gameDirectoryField.textProperty().addListener((_, _, newValue) -> {
            if (newValue != null && !newValue.trim().isEmpty()) {
                preferences.put(GAME_DIRECTORY_KEY, newValue.trim());
                updateGameDirectoryLabel();
            }
        });
        refreshAccounts();
        refreshInstalledProfiles(null);
        showPlayPage();
        loadVersions(mirrorComboBox.getValue());
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

    private void showPage(javafx.scene.layout.VBox page, Button activeButton) {
        playPage.setVisible(page == playPage);
        playPage.setManaged(page == playPage);
        downloadPage.setVisible(page == downloadPage);
        downloadPage.setManaged(page == downloadPage);
        settingsPage.setVisible(page == settingsPage);
        settingsPage.setManaged(page == settingsPage);
        playNavButton.setStyle(navStyle(playNavButton == activeButton));
        downloadNavButton.setStyle(navStyle(downloadNavButton == activeButton));
        settingsNavButton.setStyle(navStyle(settingsNavButton == activeButton));
    }

    private static String navStyle(boolean active) {
        return active
                ? "-fx-background-color: #29233d; -fx-background-radius: 8; -fx-text-fill: #c4b5fd; -fx-alignment: CENTER_LEFT;"
                : "-fx-background-color: transparent; -fx-background-radius: 8; -fx-text-fill: #a1a1aa; -fx-alignment: CENTER_LEFT;";
    }

    @FXML
    private void onLoaderMenuClick() {
        MinecraftInstaller.VersionEntry minecraftVersion = versionComboBox.getValue();
        if (minecraftVersion == null) {
            statusLabel.setText("请先选择 Minecraft 版本");
            return;
        }

        Optional<LoaderType> installed = new LoaderInstallDialog().showAndWait(
                launchButton.getScene().getWindow(), minecraftVersion.getId(), getGameDirectory());
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
        accountStore.addAccount(name);
        refreshAccounts();
        statusLabel.setText("已添加离线账号 " + name);
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
        accountStore.removeAccount(selected);
        refreshAccounts();
        refreshAccountList();
        statusLabel.setText("已删除离线账号 " + selected);
    }

    @FXML
    private void onRefreshInstalledProfilesClick() {
        refreshInstalledProfiles(null);
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
        confirmation.setContentText("只删除该版本目录；共享库、资源文件、存档和其他版本不会删除。");
        Optional<ButtonType> result = confirmation.showAndWait();
        if (result.isEmpty() || result.get() != ButtonType.OK) {
            return;
        }

        Path versionsDirectory = getGameDirectory().resolve("versions").toAbsolutePath().normalize();
        Path profileDirectory = versionsDirectory.resolve(profile.id).normalize();
        if (!versionsDirectory.equals(profileDirectory.getParent())) {
            statusLabel.setText("无法删除该版本：版本目录无效");
            return;
        }
        if (!Files.exists(profileDirectory, LinkOption.NOFOLLOW_LINKS)) {
            statusLabel.setText("该版本目录已不存在");
            refreshInstalledProfiles(null);
            return;
        }

        try {
            deleteProfileDirectory(profileDirectory);
            statusLabel.setText("已删除版本 " + profile.id);
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
        accountStore.selectAccount(selected);
        refreshAccounts();
        statusLabel.setText("已将 " + selected + " 设为当前账号");
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
        Path gameDirectory = getGameDirectory();
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
            gameProcess = gameLauncher.launch(profile.id, account, getGameDirectory());
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
        Path versionsDirectory = getGameDirectory().resolve("versions");
        if (!Files.isDirectory(versionsDirectory)) {
            profileListView.getItems().clear();
            installedProfilesLabel.setText("0 个已安装版本");
            updateLaunchButton();
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
                    profiles.add(new InstalledProfile(id, "原版"));
                } else if (parent != null && parent.matches("[A-Za-z0-9._-]+")
                        && Files.isRegularFile(versionsDirectory.resolve(parent)
                        .resolve(parent + ".jar"))) {
                    profiles.add(new InstalledProfile(id, loaderName(id)));
                }
            }
        } catch (IOException exception) {
            statusLabel.setText("读取已安装版本失败：" + messageFor(exception));
        }
        profileListView.getItems().setAll(profiles);
        installedProfilesLabel.setText(profiles.size() + " 个已安装版本");
        InstalledProfile selection = findProfile(profiles, oldSelection == null ? null : oldSelection.id);
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

    private static InstalledProfile findProfile(List<InstalledProfile> profiles, String id) {
        if (id == null) {
            return null;
        }
        for (InstalledProfile profile : profiles) {
            if (profile.id.equals(id)) {
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

        private InstalledProfile(String id, String type) {
            this.id = id;
            this.type = type;
        }

        @Override
        public String toString() {
            return id.equals(type) ? id : type + " · " + id;
        }
    }

    private Path getGameDirectory() {
        String configured = preferences.get(GAME_DIRECTORY_KEY, "");
        if (!configured.trim().isEmpty()) {
            return Paths.get(configured).toAbsolutePath().normalize();
        }
        return Paths.get(System.getProperty("user.home"), ".minecraft").toAbsolutePath().normalize();
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
