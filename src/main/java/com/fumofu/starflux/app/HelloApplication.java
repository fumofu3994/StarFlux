package com.fumofu.starflux.app;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.io.IOException;

public class HelloApplication extends Application {
    @Override
    public void start(Stage stage) throws IOException {
        FXMLLoader fxmlLoader = new FXMLLoader(
                HelloApplication.class.getResource("/com/fumofu/starflux/hello-view.fxml"));
        Scene scene = new Scene(fxmlLoader.load(), 1040, 760);
        scene.getStylesheets().add(HelloApplication.class
                .getResource("/com/fumofu/starflux/launcher.css").toExternalForm());
        stage.setMinWidth(900);
        stage.setMinHeight(650);
        stage.setTitle("StarFlux MC Launcher");
        stage.setScene(scene);
        LauncherController controller = fxmlLoader.getController();
        stage.setOnCloseRequest(event -> controller.close());
        stage.show();
    }
}
