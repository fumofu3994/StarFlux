package com.fumofu.starflux;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.io.IOException;

public class HelloApplication extends Application {
    @Override
    public void start(Stage stage) throws IOException {
        FXMLLoader fxmlLoader = new FXMLLoader(HelloApplication.class.getResource("hello-view.fxml"));
        Scene scene = new Scene(fxmlLoader.load(), 860, 560);
        stage.setMinWidth(760);
        stage.setMinHeight(500);
        stage.setTitle("StarFlux MC Launcher");
        stage.setScene(scene);
        stage.show();
    }
}
