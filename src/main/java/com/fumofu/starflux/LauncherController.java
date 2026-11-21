package com.fumofu.starflux;

import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextInputDialog;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.concurrent.atomic.AtomicLong;

public class LauncherController {
    @FXML
    private ComboBox<MirrorSource> mirrorComboBox;
    @FXML
    private ComboBox<MinecraftInstaller.VersionEntry> versionComboBox;
    @FXML
    private ComboBox<String> accountComboBox;
    @FXML
    private ComboBox<LoaderType> loaderTypeComboBox;
    @FXML
    private ComboBox<String> loaderVersionComboBox;
    @FXML
    private ComboBox<InstalledProfile> profileComboBox;
    @FXML
    private Button installButton;
    @FXML
    private Button launchButton;
    @FXML
    private Button installLoaderButton;
    @FXML
    private ProgressBar progressBar;
    @FXML
    private Label statusLabel;
    @FXML
    private Label gameDirectoryLabel;

    private final OfflineAccountStore accountStore = new OfflineAccountStore();
    private final MinecraftGameLauncher gameLauncher = new MinecraftGameLauncher();
    private final LoaderInstaller loaderInstaller = new LoaderInstaller();
    private final AtomicLong loaderRequest = new AtomicLong();
    private Process gameProcess;

    @FXML
    private void initialize() {
        Path gameDirectory = getGameDirectory();
        gameDirectoryLabel.setText("游戏目录  " + gameDirectory.toAbsolutePath());

        mirrorComboBox.getItems().setAll(MirrorSource.values());
        mirrorComboBox.getSelectionModel().select(MirrorSource.MOJANG);
        mirrorComboBox.valueProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue != null && newValue != oldValue) {
                loadVersions(newValue);
            }
        });
        versionComboBox.valueProperty().addListener((observable, oldValue, newValue) ->
                {
                    refreshInstalledProfiles(null);
                    loadLoaderVersions();
                });
        loaderTypeComboBox.getItems().setAll(LoaderType.values());
        loaderTypeComboBox.getSelectionModel().select(LoaderType.FABRIC);
        loaderTypeComboBox.valueProperty().addListener((observable, oldValue, newValue) ->
                loadLoaderVersions());
        profileComboBox.valueProperty().addListener((observable, oldValue, newValue) ->
                updateLaunchButton());
        accountComboBox.valueProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue != null) {
                accountStore.selectAccount(newValue);
            }
            updateLaunchButton();
        });
        refreshAccounts();
        loadVersions(mirrorComboBox.getValue());
    }

    private void loadLoaderVersions() {
        MinecraftInstaller.VersionEntry minecraftVersion = versionComboBox.getValue();
        LoaderType loaderType = loaderTypeComboBox.getValue();
        long request = loaderRequest.incrementAndGet();
        loaderVersionComboBox.getItems().clear();
        installLoaderButton.setDisable(true);
        if (minecraftVersion == null || loaderType == null) {
            return;
        }

        loaderVersionComboBox.setDisable(true);
        Task<List<String>> task = new Task<List<String>>() {
            @Override
            protected List<String> call() throws Exception {
                return loaderInstaller.fetchVersions(loaderType, minecraftVersion.getId());
            }
        };
        task.setOnSucceeded(event -> {
            if (request != loaderRequest.get()) {
                return;
            }
            loaderVersionComboBox.getItems().setAll(task.getValue());
            loaderVersionComboBox.setDisable(false);
            if (!loaderVersionComboBox.getItems().isEmpty()) {
                loaderVersionComboBox.getSelectionModel().selectFirst();
                installLoaderButton.setDisable(false);
            } else {
                statusLabel.setText(loaderType + " 没有适用于 Minecraft "
                        + minecraftVersion.getId() + " 的版本");
            }
        });
        task.setOnFailed(event -> {
            if (request != loaderRequest.get()) {
                return;
            }
            loaderVersionComboBox.setDisable(false);
            statusLabel.setText("加载 " + loaderType + " 版本失败：" + messageFor(task.getException()));
        });
        new Thread(task, "loader-versions-" + loaderType.name().toLowerCase()).start();
    }

    @FXML
    private void onInstallLoaderClick() {
        MinecraftInstaller.VersionEntry minecraftVersion = versionComboBox.getValue();
        LoaderType loaderType = loaderTypeComboBox.getValue();
        String loaderVersion = loaderVersionComboBox.getValue();
        if (minecraftVersion == null || loaderType == null || loaderVersion == null) {
            statusLabel.setText("请选择 Minecraft 版本、加载器和加载器版本");
            return;
        }

        Path gameDirectory = getGameDirectory();
        setBusy(true);
        progressBar.setProgress(-1);
        statusLabel.setText("正在安装 " + loaderType + "...");
        Task<Void> task = new Task<Void>() {
            @Override
            protected Void call() throws Exception {
                loaderInstaller.install(loaderType, minecraftVersion.getId(), loaderVersion, gameDirectory,
                        message -> Platform.runLater(() -> statusLabel.setText(message)));
                return null;
            }
        };
        task.setOnSucceeded(event -> {
            statusLabel.setText(loaderType + " 安装完成");
            progressBar.setProgress(1);
            setBusy(false);
            refreshInstalledProfiles(loaderType.name().toLowerCase());
            updateLaunchButton();
        });
        task.setOnFailed(event -> {
            statusLabel.setText("安装 " + loaderType + " 失败：" + messageFor(task.getException()));
            progressBar.setProgress(0);
            setBusy(false);
            loadLoaderVersions();
            updateLaunchButton();
        });
        new Thread(task, "loader-install-" + loaderType.name().toLowerCase()).start();
    }

    private void loadVersions(MirrorSource mirrorSource) {
        final MinecraftInstaller selectedInstaller = new MinecraftInstaller(mirrorSource);
        progressBar.setProgress(-1);
        statusLabel.setText("正在从 " + mirrorSource + " 加载 Minecraft 版本列表...");
        installButton.setDisable(true);
        launchButton.setDisable(true);
        mirrorComboBox.setDisable(true);
        versionComboBox.setDisable(true);

        Task<List<MinecraftInstaller.VersionEntry>> task = new Task<List<MinecraftInstaller.VersionEntry>>() {
            @Override
            protected List<MinecraftInstaller.VersionEntry> call() throws Exception {
                return selectedInstaller.fetchVersions();
            }
        };
        task.setOnSucceeded(event -> {
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
        task.setOnFailed(event -> {
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
        if (!result.isPresent()) {
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
        ListView<String> accounts = new ListView<String>();
        accounts.getItems().setAll(accountStore.getAccounts());
        accounts.setPrefHeight(180);
        Alert dialog = new Alert(Alert.AlertType.NONE);
        dialog.setTitle("账号管理");
        dialog.setHeaderText("离线账号");
        dialog.getDialogPane().setContent(accounts);
        javafx.scene.control.ButtonType removeButton =
                new javafx.scene.control.ButtonType("移除所选账号", javafx.scene.control.ButtonBar.ButtonData.LEFT);
        javafx.scene.control.ButtonType closeButton =
                new javafx.scene.control.ButtonType("完成", javafx.scene.control.ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getButtonTypes().setAll(removeButton, closeButton);
        dialog.setResultConverter(button -> {
            if (button == removeButton) {
                String selected = accounts.getSelectionModel().getSelectedItem();
                if (selected == null) {
                    showError("未选择账号", "请先选择要移除的账号。");
                } else {
                    accountStore.removeAccount(selected);
                    accounts.getItems().remove(selected);
                    statusLabel.setText("已移除离线账号 " + selected);
                }
                return removeButton;
            }
            return closeButton;
        });
        while (true) {
            Optional<javafx.scene.control.ButtonType> result = dialog.showAndWait();
            if (!result.isPresent() || result.get() != removeButton) {
                break;
            }
        }
        refreshAccounts();
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

        Task<Void> task = new Task<Void>() {
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
        task.setOnSucceeded(event -> {
            statusLabel.setText(version.getId() + " 安装完成");
            progressBar.setProgress(1);
            setBusy(false);
            refreshInstalledProfiles(null);
            updateLaunchButton();
        });
        task.setOnFailed(event -> {
            statusLabel.setText("安装失败：" + messageFor(task.getException()));
            progressBar.setProgress(0);
            setBusy(false);
            updateLaunchButton();
        });
        new Thread(task, "minecraft-install-" + version.getId()).start();
    }

    @FXML
    private void onLaunchClick() {
        MinecraftInstaller.VersionEntry version = versionComboBox.getValue();
        String account = accountComboBox.getValue();
        if (version == null || account == null) {
            statusLabel.setText("请选择游戏版本和离线账号");
            return;
        }
        try {
            InstalledProfile profile = profileComboBox.getValue();
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
                }
            }));
            statusLabel.setText("Minecraft " + profile.id + " 已启动（离线账号：" + account + "）");
            updateLaunchButton();
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
        updateLaunchButton();
    }

    private void updateLaunchButton() {
        if (launchButton == null) {
            return;
        }
        String account = accountComboBox.getValue();
        InstalledProfile profile = profileComboBox.getValue();
        boolean installed = profile != null && account != null;
        launchButton.setDisable(!installed || gameProcess != null || installButton.isDisabled());
        launchButton.setText(installed ? "启动游戏" : "安装后即可启动");
    }

    private void setBusy(boolean busy) {
        mirrorComboBox.setDisable(busy);
        versionComboBox.setDisable(busy);
        accountComboBox.setDisable(busy);
        loaderTypeComboBox.setDisable(busy);
        loaderVersionComboBox.setDisable(busy);
        profileComboBox.setDisable(busy);
        installButton.setDisable(busy || versionComboBox.getValue() == null);
        installLoaderButton.setDisable(busy || loaderVersionComboBox.getValue() == null);
        launchButton.setDisable(busy);
    }

    private void refreshInstalledProfiles(String preferredType) {
        MinecraftInstaller.VersionEntry version = versionComboBox.getValue();
        String oldSelection = profileComboBox.getValue() == null ? null : profileComboBox.getValue().id;
        List<InstalledProfile> profiles = new ArrayList<InstalledProfile>();
        if (version == null) {
            profileComboBox.getItems().clear();
            updateLaunchButton();
            return;
        }

        Path versionsDirectory = getGameDirectory().resolve("versions");
        if (!Files.isDirectory(versionsDirectory)) {
            profileComboBox.getItems().clear();
            updateLaunchButton();
            return;
        }
        Path vanillaDirectory = versionsDirectory.resolve(version.getId());
        if (Files.isRegularFile(vanillaDirectory.resolve(version.getId() + ".json"))
                && Files.isRegularFile(vanillaDirectory.resolve(version.getId() + ".jar"))) {
            profiles.add(new InstalledProfile(version.getId(), "原版"));
        }
        try (Stream<Path> directories = Files.list(versionsDirectory)) {
            java.util.Iterator<Path> iterator = directories.iterator();
            while (iterator.hasNext()) {
                Path directory = iterator.next();
                String id = directory.getFileName().toString();
                if (!Files.isDirectory(directory) || id.equals(version.getId())) {
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
                if (version.getId().equals(getJsonString(json, "inheritsFrom"))) {
                    profiles.add(new InstalledProfile(id, loaderName(id)));
                }
            }
        } catch (IOException exception) {
            statusLabel.setText("读取已安装版本失败：" + messageFor(exception));
        }
        profileComboBox.getItems().setAll(profiles);
        InstalledProfile selection = findProfile(profiles, oldSelection);
        if (selection == null && preferredType != null) {
            for (InstalledProfile profile : profiles) {
                if (profile.id.toLowerCase().contains(preferredType)) {
                    selection = profile;
                    break;
                }
            }
        }
        if (selection == null && !profiles.isEmpty()) {
            selection = profiles.get(0);
        }
        profileComboBox.getSelectionModel().select(selection);
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

    private static Path getGameDirectory() {
        return Paths.get(System.getProperty("user.home"), ".minecraft");
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
