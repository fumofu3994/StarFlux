package com.fumofu.starflux;

import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

public class LoaderInstallDialog {
    private final LoaderInstaller installer = new LoaderInstaller();
    private final AtomicLong requestId = new AtomicLong();

    public Optional<LoaderType> showAndWait(Window owner, String minecraftVersion, Path gameDirectory) {
        Dialog<LoaderType> dialog = new Dialog<LoaderType>();
        dialog.setTitle("模组加载器");
        dialog.setHeaderText("为 Minecraft " + minecraftVersion + " 安装可选的模组加载器");
        if (owner != null) {
            dialog.initOwner(owner);
        }

        ComboBox<LoaderType> typeComboBox = new ComboBox<LoaderType>();
        typeComboBox.getItems().setAll(LoaderType.values());
        typeComboBox.getSelectionModel().select(LoaderType.FABRIC);
        typeComboBox.setMaxWidth(Double.MAX_VALUE);

        ComboBox<String> versionComboBox = new ComboBox<String>();
        versionComboBox.setPromptText("选择加载器版本");
        versionComboBox.setMaxWidth(Double.MAX_VALUE);

        Label statusLabel = new Label("选择加载器以查看可用版本");
        statusLabel.setWrapText(true);
        ProgressBar progressBar = new ProgressBar(0);
        progressBar.setMaxWidth(Double.MAX_VALUE);

        VBox content = new VBox(10,
                new Label("加载器"),
                typeComboBox,
                new Label("加载器版本"),
                versionComboBox,
                statusLabel,
                progressBar);
        content.setPadding(new Insets(8, 0, 0, 0));
        dialog.getDialogPane().setContent(content);

        ButtonType installButtonType = new ButtonType("安装", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelButtonType = new ButtonType("取消", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().setAll(installButtonType, cancelButtonType);
        Button installButton = (Button) dialog.getDialogPane().lookupButton(installButtonType);
        installButton.setDisable(true);

        typeComboBox.valueProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue != null && newValue != oldValue) {
                loadVersions(newValue, minecraftVersion, versionComboBox, installButton, statusLabel, progressBar);
            }
        });
        versionComboBox.valueProperty().addListener((observable, oldValue, newValue) ->
                installButton.setDisable(newValue == null || versionComboBox.isDisabled()));

        installButton.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            LoaderType loaderType = typeComboBox.getValue();
            String loaderVersion = versionComboBox.getValue();
            if (loaderType == null || loaderVersion == null) {
                event.consume();
                return;
            }
            event.consume();
            installButton.setDisable(true);
            typeComboBox.setDisable(true);
            versionComboBox.setDisable(true);
            progressBar.setProgress(-1);
            statusLabel.setText("正在安装 " + loaderType + "...");

            Task<Void> task = new Task<Void>() {
                @Override
                protected Void call() throws Exception {
                    installer.install(loaderType, minecraftVersion, loaderVersion, gameDirectory,
                            message -> Platform.runLater(() -> statusLabel.setText(message)));
                    return null;
                }
            };
            task.setOnSucceeded(done -> {
                statusLabel.setText(loaderType + " 安装完成");
                progressBar.setProgress(1);
                dialog.setResult(loaderType);
                dialog.close();
            });
            task.setOnFailed(failed -> {
                statusLabel.setText("安装失败：" + messageFor(task.getException())
                        + "。可查看 .minecraft/logs 中的安装日志。");
                progressBar.setProgress(0);
                typeComboBox.setDisable(false);
                versionComboBox.setDisable(false);
                installButton.setDisable(versionComboBox.getValue() == null);
            });
            new Thread(task, "loader-install-" + loaderType.name().toLowerCase()).start();
        });

        loadVersions(LoaderType.FABRIC, minecraftVersion, versionComboBox, installButton, statusLabel, progressBar);
        return dialog.showAndWait();
    }

    private void loadVersions(LoaderType type, String minecraftVersion, ComboBox<String> versionComboBox,
                              Button installButton, Label statusLabel, ProgressBar progressBar) {
        long request = requestId.incrementAndGet();
        versionComboBox.getItems().clear();
        versionComboBox.setDisable(true);
        installButton.setDisable(true);
        progressBar.setProgress(-1);
        statusLabel.setText("正在加载 " + type + " 版本...");

        Task<List<String>> task = new Task<List<String>>() {
            @Override
            protected List<String> call() throws Exception {
                return installer.fetchVersions(type, minecraftVersion);
            }
        };
        task.setOnSucceeded(event -> {
            if (request != requestId.get()) {
                return;
            }
            versionComboBox.getItems().setAll(task.getValue());
            versionComboBox.setDisable(false);
            progressBar.setProgress(0);
            if (versionComboBox.getItems().isEmpty()) {
                statusLabel.setText(type + " 没有适用于 Minecraft " + minecraftVersion + " 的版本");
            } else {
                versionComboBox.getSelectionModel().selectFirst();
                statusLabel.setText("已找到 " + versionComboBox.getItems().size() + " 个版本");
                installButton.setDisable(false);
            }
        });
        task.setOnFailed(event -> {
            if (request != requestId.get()) {
                return;
            }
            versionComboBox.setDisable(false);
            progressBar.setProgress(0);
            statusLabel.setText("加载 " + type + " 版本失败：" + messageFor(task.getException()));
        });
        new Thread(task, "loader-versions-" + type.name().toLowerCase()).start();
    }

    private static String messageFor(Throwable error) {
        if (error == null || error.getMessage() == null || error.getMessage().trim().isEmpty()) {
            return "未知错误";
        }
        return error.getMessage();
    }
}
