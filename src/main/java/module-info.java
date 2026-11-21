module com.fumofu.starflux {
    requires javafx.controls;
    requires javafx.fxml;
    requires com.google.gson;
    requires java.prefs;


    opens com.fumofu.starflux to javafx.fxml;
    exports com.fumofu.starflux;
}