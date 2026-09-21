module com.priolab {
    requires javafx.controls;
    requires javafx.fxml;
    requires java.sql;
    requires com.fasterxml.jackson.databind;

    // FXML instantiates controllers and injects @FXML fields via reflection.
    opens com.priolab to javafx.fxml;
    opens com.priolab.controller to javafx.fxml;

    // Jackson (de)serializes config POJOs via reflection.
    opens com.priolab.config to com.fasterxml.jackson.databind;

    exports com.priolab;
}
