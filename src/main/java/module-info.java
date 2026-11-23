module com.fumofu.starflux {
    requires javafx.controls;
    requires javafx.fxml;
    requires com.google.gson;
    requires java.desktop;
    requires java.prefs;
    requires jdk.management;
    requires java.net.http;


    opens com.fumofu.starflux.app to javafx.fxml, javafx.graphics;
    exports com.fumofu.starflux.app;
    exports com.fumofu.starflux;
}