module com.example.demo {
    requires javafx.controls;
    requires javafx.fxml;

    // Apache POI (these are automatic module names from the POI jars)
    requires org.apache.poi.poi;
    requires org.apache.poi.ooxml;

    requires java.prefs;

    opens com.example.demo to javafx.fxml;
    exports com.example.demo;
}