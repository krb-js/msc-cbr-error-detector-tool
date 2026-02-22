package com.example.demo;

import javafx.application.HostServices;
import javafx.application.Platform;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.css.PseudoClass;
import javafx.fxml.FXML;

import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Alert;

import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.AnchorPane;
import javafx.scene.text.Text;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.prefs.Preferences;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class HelloController {

    // Root (needed for dark mode switching)
    @FXML private AnchorPane rootPane;

    @FXML private TableView<FileItem> tableView;
    @FXML private TableColumn<FileItem, String> filenameColumn;
    @FXML private TableColumn<FileItem, String> tidColumn;
    @FXML private TableColumn<FileItem, String> unhCountColumn;
    @FXML private TableColumn<FileItem, String> documentIDColumn;

    @FXML private ProgressBar progressBar;
    @FXML private Label progressLabel;

    @FXML private TextArea aspectInCollectionTextArea;
    @FXML private TextArea resultsTextArea;

    @FXML private Button analyzeButton;
    @FXML private Button exportButton;
    @FXML private Button copyErrorsButton;

    // Dark mode toggle
    @FXML private ToggleButton darkModeToggle;

    private final ObservableList<FileItem> fileItems = FXCollections.observableArrayList();
    private HostServices hostServices;

    // ===== Preferences (Option B: auto-expire saved MSC codes) =====
    private static final String PREF_KEY_MSC_CODES = "msc_codes_text";
    private static final String PREF_KEY_MSC_CODES_TS = "msc_codes_saved_epoch_ms";
    private static final long MSC_CODES_EXPIRY_MS = 24L * 60L * 60L * 1000L; // 24 hours

    private final Preferences prefs = Preferences.userNodeForPackage(HelloController.class);
    private boolean isLoadingPrefs = false;

    // Row highlight pseudo-classes (CSS handles the colors)
    private static final PseudoClass PC_ERROR = PseudoClass.getPseudoClass("error");
    private static final PseudoClass PC_CLEAN = PseudoClass.getPseudoClass("clean");

    // Stylesheets (place these files in your resources folder, same package as FXML is fine)
    private static final String LIGHT_CSS = "light.css";
    private static final String DARK_CSS = "dark.css";

    public void setHostServices(HostServices hostServices) {
        this.hostServices = hostServices;
    }

    @FXML
    private void initialize() {

        filenameColumn.setCellValueFactory(data -> data.getValue().filenameProperty());
        tidColumn.setCellValueFactory(data -> data.getValue().tidProperty());
        unhCountColumn.setCellValueFactory(data -> data.getValue().unhCountProperty());
        documentIDColumn.setCellValueFactory(data -> data.getValue().documentIDProperty());

        // TID column as JavaFX Hyperlink (fully qualified so it never conflicts with POI)
        tidColumn.setCellFactory(tc -> new TableCell<>() {
            private final javafx.scene.control.Hyperlink link = new javafx.scene.control.Hyperlink();
            {
                link.setOnAction(event -> {
                    FileItem item = getTableView().getItems().get(getIndex());
                    if (item != null && hostServices != null) {
                        String url = "https://www.myvan.descartes.com/DocTrackingCore/Document/RetrieveTidStory?tid=" + item.getTid();
                        hostServices.showDocument(url);
                    }
                });
            }
            @Override
            protected void updateItem(String tid, boolean empty) {
                super.updateItem(tid, empty);
                if (empty || tid == null) setGraphic(null);
                else {
                    link.setText(tid);
                    setGraphic(link);
                }
            }
        });

        // DocumentID column wrap text
        documentIDColumn.setCellFactory(tc -> new TableCell<>() {
            private final Text text = new Text();
            {
                text.wrappingWidthProperty().bind(tc.widthProperty().subtract(15));
                setGraphic(text);
                setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
            }
            @Override
            protected void updateItem(String value, boolean empty) {
                super.updateItem(value, empty);
                if (empty || value == null || value.isBlank()) {
                    text.setText("");
                    setGraphic(null);
                } else {
                    text.setText(value);
                    setGraphic(text);
                }
            }
        });

        tableView.setItems(fileItems);

        // ✅ Row highlighting based on FileItem.hasErrors
        tableView.setRowFactory(tv -> {
            TableRow<FileItem> row = new TableRow<>();

            row.itemProperty().addListener((obs, oldItem, newItem) -> {
                // reset
                row.pseudoClassStateChanged(PC_ERROR, false);
                row.pseudoClassStateChanged(PC_CLEAN, false);

                if (newItem == null) return;

                // when the hasErrors property changes, update row style
                newItem.hasErrorsProperty().addListener((o, oldV, newV) -> {
                    if (row.getItem() == newItem) {
                        row.pseudoClassStateChanged(PC_ERROR, Boolean.TRUE.equals(newV));
                        row.pseudoClassStateChanged(PC_CLEAN, !Boolean.TRUE.equals(newV));
                    }
                });

                boolean isError = newItem.hasErrorsProperty().get();
                row.pseudoClassStateChanged(PC_ERROR, isError);
                row.pseudoClassStateChanged(PC_CLEAN, !isError);
            });

            return row;
        });

        progressBar.setProgress(0);
        progressLabel.setText("0 / 0");

        BooleanBinding canAnalyze = new BooleanBinding() {
            { super.bind(fileItems, aspectInCollectionTextArea.textProperty()); }
            @Override
            protected boolean computeValue() {
                return fileItems.isEmpty()
                        || aspectInCollectionTextArea.getText() == null
                        || aspectInCollectionTextArea.getText().trim().isEmpty();
            }
        };

        analyzeButton.disableProperty().bind(canAnalyze);
        exportButton.setDisable(true);
        copyErrorsButton.setDisable(true);

        // ===== Option B: Load saved MSC codes only if not expired =====
        isLoadingPrefs = true;

        String savedCodes = prefs.get(PREF_KEY_MSC_CODES, "");
        long savedAt = prefs.getLong(PREF_KEY_MSC_CODES_TS, 0L);

        boolean hasSavedCodes = savedCodes != null && !savedCodes.isBlank();
        boolean isExpired = savedAt == 0L || (System.currentTimeMillis() - savedAt) > MSC_CODES_EXPIRY_MS;

        if (hasSavedCodes && !isExpired) {
            aspectInCollectionTextArea.setText(savedCodes);
        } else if (hasSavedCodes) {
            resultsTextArea.setText(
                    "MSC codes were previously saved, but they are older than 24 hours.\n" +
                            "Please paste the latest Customer Code table before analyzing."
            );
        }

        isLoadingPrefs = false;

        // If MSC codes change: clear results, disable export/copy, reset row highlights, save w/ timestamp
        aspectInCollectionTextArea.textProperty().addListener((obs, oldVal, newVal) -> {
            if (isLoadingPrefs) return;

            resultsTextArea.clear();
            exportButton.setDisable(true);
            copyErrorsButton.setDisable(true);

            // reset all items to "clean" until next analysis
            for (FileItem item : fileItems) item.setHasErrors(false);
            tableView.refresh();

            String valueToSave = (newVal == null) ? "" : newVal;
            prefs.put(PREF_KEY_MSC_CODES, valueToSave);
            prefs.putLong(PREF_KEY_MSC_CODES_TS, System.currentTimeMillis());
        });

        // Apply default theme on startup
        Platform.runLater(() -> applyTheme(false));
    }

    // ===== Dark mode toggle handler =====
    @FXML
    private void toggleDarkMode() {
        boolean dark = darkModeToggle != null && darkModeToggle.isSelected();
        applyTheme(dark);
    }

    private void applyTheme(boolean dark) {
        if (rootPane == null || rootPane.getScene() == null) return;

        String css = dark ? DARK_CSS : LIGHT_CSS;
        var url = getClass().getResource(css);
        if (url == null) {
            return;
        }
        rootPane.getScene().getStylesheets().setAll(url.toExternalForm());
    }

    @FXML
    private void copyResultsToClipboard() {
        String results = resultsTextArea.getText();
        if (results != null && !results.isBlank()) {
            ClipboardContent content = new ClipboardContent();
            content.putString(results);
            Clipboard.getSystemClipboard().setContent(content);
        } else {
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle("Copy to Clipboard");
            alert.setHeaderText(null);
            alert.setContentText("No results to copy!");
            alert.showAndWait();
        }
    }

    @FXML
    private void handleUpload() {
        fileItems.clear();
        tableView.getItems().clear();

        resultsTextArea.clear();
        exportButton.setDisable(true);
        copyErrorsButton.setDisable(true);

        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Select ZIP or TXT Files");
        fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("ZIP & TXT Files", "*.zip", "*.txt")
        );

        List<File> selectedFiles = fileChooser.showOpenMultipleDialog(new Stage());
        if (selectedFiles == null || selectedFiles.isEmpty()) return;

        Task<Void> task = new Task<>() {
            @Override
            protected Void call() {
                try {
                    List<FileOrZipEntry> allTxtFiles = new ArrayList<>();
                    for (File file : selectedFiles) {
                        if (file.getName().toLowerCase().endsWith(".zip")) {
                            allTxtFiles.addAll(listTxtInZip(file));
                        } else if (file.getName().toLowerCase().endsWith(".txt")) {
                            allTxtFiles.add(new FileOrZipEntry(file.getName(), file, null));
                        }
                    }

                    int totalTxt = allTxtFiles.size();
                    int processed = 0;

                    for (FileOrZipEntry entry : allTxtFiles) {
                        if (entry.zipInputStream != null) {
                            processTxtFile(entry.name, entry.zipInputStream);
                        } else {
                            processTxtFile(entry.name, new FileInputStream(entry.file));
                        }

                        processed++;
                        int current = processed;
                        Platform.runLater(() -> progressLabel.setText("Processing " + current + " of " + totalTxt));
                        updateProgress(processed, totalTxt);
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
                return null;
            }
        };

        progressBar.progressProperty().bind(task.progressProperty());
        new Thread(task).start();
    }

    private static class FileOrZipEntry {
        String name;
        File file;
        InputStream zipInputStream;
        FileOrZipEntry(String name, File file, InputStream zipInputStream) {
            this.name = name;
            this.file = file;
            this.zipInputStream = zipInputStream;
        }
    }

    private List<FileOrZipEntry> listTxtInZip(File zipFile) throws IOException {
        List<FileOrZipEntry> entries = new ArrayList<>();
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (!entry.isDirectory() && entry.getName().toLowerCase().endsWith(".txt")) {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    byte[] buffer = new byte[1024];
                    int read;
                    while ((read = zis.read(buffer)) != -1) baos.write(buffer, 0, read);
                    ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
                    entries.add(new FileOrZipEntry(entry.getName(), null, bais));
                }
                zis.closeEntry();
            }
        }
        return entries;
    }

    private void processTxtFile(String filename, InputStream inputStream) {
        int unhCount = 0;
        int nadCount = 0;
        List<String> bgm02List = new ArrayList<>();
        List<String> nadZZZList = new ArrayList<>();

        String tid = filename.replace(".txt", "");

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
            String line;
            String currentBGM = "";
            String currentNAD = "no NAD+ZZZ";
            boolean inUnh = false;

            while ((line = reader.readLine()) != null) {
                String[] segments = line.split("'");

                for (String segment : segments) {
                    segment = segment.trim();
                    if (segment.isEmpty()) continue;

                    if (segment.startsWith("UNH+")) {
                        if (inUnh) {
                            bgm02List.add(currentBGM.isEmpty() ? "No BGM02" : currentBGM);
                            nadZZZList.add(currentNAD);
                        }
                        inUnh = true;
                        unhCount++;
                        currentBGM = "";
                        currentNAD = "no NAD+ZZZ";
                        continue;
                    }

                    if (!inUnh) continue;

                    if (segment.startsWith("BGM+")) {
                        String[] parts = segment.split("\\+");
                        if (parts.length > 2) currentBGM = parts[2].trim();
                    }

                    if (segment.startsWith("NAD+")) {
                        String[] parts = segment.split("\\+");
                        if (parts.length > 2 && "ZZZ".equals(parts[1])) {
                            String nadValue = parts[2];
                            int sepIndex = nadValue.indexOf('_');
                            if (sepIndex >= 0) nadValue = nadValue.substring(sepIndex + 1);
                            sepIndex = nadValue.indexOf(':');
                            if (sepIndex >= 0) nadValue = nadValue.substring(0, sepIndex);
                            nadValue = nadValue.trim();
                            if (!nadValue.isEmpty()) {
                                currentNAD = nadValue;
                                nadCount++;
                            }
                        }
                    }
                }
            }

            if (inUnh) {
                bgm02List.add(currentBGM.isEmpty() ? "No BGM02" : currentBGM);
                nadZZZList.add(currentNAD);
            }

        } catch (IOException e) {
            e.printStackTrace();
        }

        List<String> combined = new ArrayList<>();
        int size = Math.max(bgm02List.size(), nadZZZList.size());
        for (int i = 0; i < size; i++) {
            String bgm = i < bgm02List.size() ? bgm02List.get(i) : "No BGM02";
            String nad = i < nadZZZList.size() ? nadZZZList.get(i) : "no NAD+ZZZ";
            combined.add(bgm + "/" + nad);
        }

        final String documentIDs = String.join("\n", combined);
        final String finalTid = tid;
        final String finalFilename = filename;
        final String finalUnhCount = "UNH: " + unhCount + " / NAD: " + nadCount;

        Platform.runLater(() ->
                fileItems.add(new FileItem(finalFilename, finalTid, finalUnhCount, documentIDs))
        );
    }

    @FXML
    private void checkAspectInCollection() {

        resultsTextArea.clear();
        exportButton.setDisable(true);
        copyErrorsButton.setDisable(true);

        // reset highlights before recalculating
        for (FileItem item : fileItems) item.setHasErrors(false);

        String text = aspectInCollectionTextArea.getText();
        Set<String> aspectCodes = new HashSet<>();

        if (text != null) {
            for (String line : text.split("\\R")) {
                line = line.trim();
                if (line.contains("aspectInCollection(customer_code,")) {
                    int start = line.indexOf('(');
                    int end = line.indexOf(')');
                    if (start >= 0 && end > start) {
                        String code = line.substring(start + 1, end)
                                .split(",")[1]
                                .trim()
                                .toUpperCase();
                        aspectCodes.add(code);
                    }
                }
            }
        }

        StringBuilder errorDetails = new StringBuilder();
        boolean errorsFound = false;

        int totalFiles = fileItems.size();
        int tidsWithErrors = 0;
        int totalErrorCount = 0;

        for (FileItem item : fileItems) {

            List<String> noNAD = new ArrayList<>();
            Map<String, List<String>> missingNAD = new LinkedHashMap<>();

            for (String line : item.getDocumentID().split("\\R")) {

                String[] parts = line.split("/");
                if (parts.length < 2) continue;

                String bgm = parts[0];
                String nad = parts[1].trim();

                if ("no NAD+ZZZ".equalsIgnoreCase(nad)) {
                    noNAD.add(bgm);
                } else {
                    String nadUpper = nad.toUpperCase();
                    if (!aspectCodes.contains(nadUpper)) {
                        missingNAD.computeIfAbsent(nadUpper, k -> new ArrayList<>()).add(bgm);
                    }
                }
            }

            if (!missingNAD.isEmpty() || !noNAD.isEmpty()) {

                // ✅ mark row as error for highlighting
                item.setHasErrors(true);

                errorsFound = true;
                tidsWithErrors++;

                errorDetails.append("ERRORS!\nTID: ")
                        .append(item.getTid())
                        .append("\n");

                for (Map.Entry<String, List<String>> entry : missingNAD.entrySet()) {
                    totalErrorCount++;
                    errorDetails.append("Error: ")
                            .append(entry.getKey())
                            .append(" is not present in the Customer Code table\n");
                    errorDetails.append("DocumentID:\n");
                    entry.getValue().forEach(bgm -> errorDetails.append(bgm).append("\n"));
                }

                if (!noNAD.isEmpty()) {
                    totalErrorCount++;
                    errorDetails.append("Error: No NAD+ZZZ present\n");
                    errorDetails.append("DocumentID:\n");
                    noNAD.forEach(bgm -> errorDetails.append(bgm).append("\n"));
                }

                errorDetails.append("\n");
            }
        }

        tableView.refresh();

        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        String timestamp = LocalDateTime.now().format(dtf);

        StringBuilder finalOutput = new StringBuilder();

        finalOutput.append("===== VALIDATION SUMMARY =====\n")
                .append("Files Scanned: ").append(totalFiles).append("\n")
                .append("TIDs With Errors: ").append(tidsWithErrors).append("\n")
                .append("Total Error Types: ").append(totalErrorCount).append("\n")
                .append("Timestamp: ").append(timestamp).append("\n")
                .append("==============================\n\n");

        if (!errorsFound) {

            finalOutput.append("No errors detected.\n")
                    .append("All TIDs passed aspectInCollection checks.\n");

            resultsTextArea.setText(finalOutput.toString());

            exportButton.setDisable(true);
            copyErrorsButton.setDisable(true);

        } else {

            finalOutput.append(errorDetails);

            resultsTextArea.setText(finalOutput.toString());

            exportButton.setDisable(false);
            copyErrorsButton.setDisable(false);
        }
    }

    @FXML
    private void copyErrorsOnly() {

        String results = resultsTextArea.getText();
        if (results == null || results.isBlank()) return;

        int summaryEnd = results.indexOf("==============================");
        if (summaryEnd < 0) return;

        String errorsPart = results.substring(summaryEnd + 30).trim();

        if (!errorsPart.contains("ERRORS!")) {
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle("Copy Errors");
            alert.setHeaderText(null);
            alert.setContentText("No errors to copy!");
            alert.showAndWait();
            return;
        }

        ClipboardContent content = new ClipboardContent();
        content.putString(errorsPart);
        Clipboard.getSystemClipboard().setContent(content);
    }

    @FXML
    private void exportToExcel() {
        if (fileItems.isEmpty()) {
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle("Export Excel");
            alert.setHeaderText(null);
            alert.setContentText("No data to export!");
            alert.showAndWait();
            return;
        }

        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
        String timestamp = LocalDateTime.now().format(dtf);

        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Save Excel File");
        fileChooser.setInitialFileName("MSC_Error_Report_" + timestamp + ".xlsx");
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Excel Files", "*.xlsx"));
        File file = fileChooser.showSaveDialog(new Stage());
        if (file == null) return;

        try (XSSFWorkbook workbook = new XSSFWorkbook()) {

            CreationHelper creationHelper = workbook.getCreationHelper();
            Sheet sheet = workbook.createSheet("MSC Errors");
            int rowNum = 0;

            Row header = sheet.createRow(rowNum++);
            header.createCell(0).setCellValue("Filename");
            header.createCell(1).setCellValue("TID");
            header.createCell(2).setCellValue("UNH/NAD+ZZZ Count");
            header.createCell(3).setCellValue("DocumentID / CustomerCode");
            header.createCell(4).setCellValue("Error Summary");

            CellStyle hlinkStyle = workbook.createCellStyle();
            Font hlinkFont = workbook.createFont();
            hlinkFont.setUnderline(Font.U_SINGLE);
            hlinkFont.setColor(IndexedColors.BLUE.getIndex());
            hlinkStyle.setFont(hlinkFont);

            CellStyle wrapStyle = workbook.createCellStyle();
            wrapStyle.setWrapText(true);

            for (FileItem item : fileItems) {

                Row row = sheet.createRow(rowNum++);

                row.createCell(0).setCellValue(item.getFilename());

                Cell tidCell = row.createCell(1);
                tidCell.setCellValue(item.getTid());

                // ✅ FIX: force POI Hyperlink type (prevents conflict with JavaFX Hyperlink)
                org.apache.poi.ss.usermodel.Hyperlink tidHyperlink =
                        creationHelper.createHyperlink(org.apache.poi.common.usermodel.HyperlinkType.URL);

                tidHyperlink.setAddress("https://www.myvan.descartes.com/DocTrackingCore/Document/RetrieveTidStory?tid=" + item.getTid());
                tidCell.setHyperlink(tidHyperlink);
                tidCell.setCellStyle(hlinkStyle);

                row.createCell(2).setCellValue(item.getUnhCount());

                Cell docCell = row.createCell(3);
                String[] docLines = item.getDocumentID().split("\\R");
                StringBuilder docCellText = new StringBuilder();
                for (String line : docLines) {
                    line = line.trim();
                    if (!line.isEmpty()) docCellText.append(line).append("\n");
                }
                docCell.setCellValue(docCellText.toString().trim());
                docCell.setCellStyle(wrapStyle);

                Cell summaryCell = row.createCell(4);
                StringBuilder summaryBuilder = new StringBuilder();
                String[] resultsLines = resultsTextArea.getText().split("\\R");
                boolean inBlock = false;
                for (String line : resultsLines) {
                    if (line.trim().equalsIgnoreCase("ERRORS!")) continue;
                    if (line.contains("TID: " + item.getTid())) {
                        inBlock = true;
                        summaryBuilder.append(line).append("\n");
                    } else if (line.startsWith("TID: ") && inBlock) {
                        inBlock = false;
                    } else if (inBlock) {
                        summaryBuilder.append(line).append("\n");
                    }
                }
                summaryCell.setCellValue(summaryBuilder.toString().trim());
                summaryCell.setCellStyle(wrapStyle);

                row.setHeight((short) -1);
            }

            for (int i = 0; i <= 4; i++) sheet.autoSizeColumn(i);

            try (FileOutputStream fos = new FileOutputStream(file)) {
                workbook.write(fos);
            }

            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle("Export Excel");
            alert.setHeaderText(null);
            alert.setContentText("Excel exported successfully with clickable TIDs!");
            alert.showAndWait();

        } catch (IOException e) {
            e.printStackTrace();
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("Export Excel");
            alert.setHeaderText("Error exporting Excel");
            alert.setContentText(e.getMessage());
            alert.showAndWait();
        }
    }

    public static class FileItem {
        private final javafx.beans.property.SimpleStringProperty filename;
        private final javafx.beans.property.SimpleStringProperty tid;
        private final javafx.beans.property.SimpleStringProperty unhCount;
        private final javafx.beans.property.SimpleStringProperty documentID;

        private final BooleanProperty hasErrors = new SimpleBooleanProperty(false);

        public FileItem(String filename, String tid, String unhCount, String documentID) {
            this.filename = new javafx.beans.property.SimpleStringProperty(filename);
            this.tid = new javafx.beans.property.SimpleStringProperty(tid);
            this.unhCount = new javafx.beans.property.SimpleStringProperty(unhCount);
            this.documentID = new javafx.beans.property.SimpleStringProperty(documentID);
        }

        public String getFilename() { return filename.get(); }
        public String getTid() { return tid.get(); }
        public String getUnhCount() { return unhCount.get(); }
        public String getDocumentID() { return documentID.get(); }

        public javafx.beans.property.SimpleStringProperty filenameProperty() { return filename; }
        public javafx.beans.property.SimpleStringProperty tidProperty() { return tid; }
        public javafx.beans.property.SimpleStringProperty unhCountProperty() { return unhCount; }
        public javafx.beans.property.SimpleStringProperty documentIDProperty() { return documentID; }

        public BooleanProperty hasErrorsProperty() { return hasErrors; }
        public boolean hasErrors() { return hasErrors.get(); }
        public void setHasErrors(boolean value) { hasErrors.set(value); }
    }
}