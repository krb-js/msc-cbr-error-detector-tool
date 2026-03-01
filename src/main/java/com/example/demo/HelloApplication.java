package com.example.demo;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.io.IOException;

public class HelloApplication extends Application {
    @Override
    public void start(Stage stage) throws IOException {
        // Load FXML
        FXMLLoader fxmlLoader = new FXMLLoader(HelloApplication.class.getResource("hello-view.fxml"));
        Parent root = fxmlLoader.load();  // <-- define root here

        // Get controller and pass HostServices
        HelloController controller = fxmlLoader.getController();
        controller.setHostServices(getHostServices());

        // Create scene with larger size
        Scene scene = new Scene(root, 1100, 800);

        // Apply stylesheet
        scene.getStylesheets().add(getClass().getResource("style.css").toExternalForm());

        // Set stage
        stage.setTitle("MSC CBR Error Detector");
        stage.setScene(scene);
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}