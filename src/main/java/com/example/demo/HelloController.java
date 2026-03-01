package com.example.demo;

import javafx.application.HostServices;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.css.PseudoClass;
import javafx.fxml.FXML;

import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;

import javafx.scene.layout.BorderPane;
import javafx.scene.text.Text;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class HelloController {

    // Root (NEW FXML uses BorderPane)
    @FXML private BorderPane rootPane;

    // Action buttons (left panel)
    @FXML private Button uploadButton;
    @FXML private Button loadCodesButton;

    // Left panel status labels (NEW)
    @FXML private Label lblStatus;
    @FXML private Label lblCounts;

    // Main table
    @FXML private TableView<FileItem> tableView;
    @FXML private TableColumn<FileItem, String> filenameColumn;
    @FXML private TableColumn<FileItem, String> tidColumn;
    @FXML private TableColumn<FileItem, String> unhCountColumn;
    @FXML private TableColumn<FileItem, String> documentIDColumn;

    // Progress card
    @FXML private ProgressBar progressBar;
    @FXML private Label progressLabel;
    @FXML private Label lblProgressDetail;

    // Results
    @FXML private TextArea resultsTextArea;

    // Analyze/Export
    @FXML private Button analyzeButton;
    @FXML private Button exportButton;

    // Codes UI
    @FXML private Label codesStatusLabel;

    private final ObservableList<FileItem> fileItems = FXCollections.observableArrayList();
    private HostServices hostServices;

    // Codes loaded from CSV/XLSX (column: source_value or source value)
    private final Set<String> validCodes = new HashSet<>();
    private File lastCodesFile = null;

    // Observable flag so Analyze button updates when codes are loaded
    private final BooleanProperty codesLoaded = new SimpleBooleanProperty(false);

    // Row highlight pseudo-classes (CSS handles the colors)
    private static final PseudoClass PC_ERROR = PseudoClass.getPseudoClass("error");
    private static final PseudoClass PC_CLEAN = PseudoClass.getPseudoClass("clean");

    public void setHostServices(HostServices hostServices) {
        this.hostServices = hostServices;
    }

    @FXML
    private void initialize() {

        filenameColumn.setCellValueFactory(data -> data.getValue().filenameProperty());
        tidColumn.setCellValueFactory(data -> data.getValue().tidProperty());
        unhCountColumn.setCellValueFactory(data -> data.getValue().unhCountProperty());
        documentIDColumn.setCellValueFactory(data -> data.getValue().documentIDProperty());

        // TID column as JavaFX Hyperlink (Fully qualified to avoid ambiguity)
        tidColumn.setCellFactory(tc -> new TableCell<>() {
            private final javafx.scene.control.Hyperlink link = new javafx.scene.control.Hyperlink();
            {
                link.setOnAction(event -> {
                    FileItem item = getTableView().getItems().get(getIndex());
                    if (item != null && hostServices != null) {
                        String tidForUrl = sanitizeTidForUrl(item.getTid());
                        if (tidForUrl == null || tidForUrl.isBlank()) return;

                        String url = "https://www.myvan.descartes.com/DocTrackingCore/Document/RetrieveTidStory?tid="
                                + urlEncode(tidForUrl);

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
        tableView.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        // Row highlighting based on FileItem.hasErrors
        tableView.setRowFactory(tv -> {
            TableRow<FileItem> row = new TableRow<>();

            row.itemProperty().addListener((obs, oldItem, newItem) -> {
                row.pseudoClassStateChanged(PC_ERROR, false);
                row.pseudoClassStateChanged(PC_CLEAN, false);

                if (newItem == null) return;

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
        if (lblProgressDetail != null) lblProgressDetail.setText("—");

        // Analyze enabled only when: files uploaded AND codes loaded
        analyzeButton.disableProperty().bind(
                Bindings.isEmpty(fileItems).or(codesLoaded.not())
        );

        exportButton.setDisable(true);
        updateCodesStatusLabel();

        setStatus("Idle");
        updateCounts(fileItems.size(), 0, 0);
    }

    private void setStatus(String s) {
        if (lblStatus != null) lblStatus.setText(s == null ? "" : s);
    }

    private void setProgressDetail(String s) {
        if (lblProgressDetail != null) lblProgressDetail.setText((s == null || s.isBlank()) ? "—" : s);
    }

    private void updateCounts(int files, int tidsWithErrors, int totalErrorTypes) {
        if (lblCounts != null) {
            lblCounts.setText("Files: " + files + " • TIDs with errors: " + tidsWithErrors + " • Total error types: " + totalErrorTypes);
        }
    }

    // ============================================================
    // URL helpers
    // ============================================================

    private String sanitizeTidForUrl(String tid) {
        if (tid == null) return "";
        String t = tid.trim();
        if (t.toLowerCase(Locale.ROOT).endsWith(" - copy")) {
            t = t.substring(0, t.length() - " - copy".length()).trim();
        }
        return t;
    }

    private String urlEncode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    // ============================================================
    // Codes Loader (CSV / XLSX)
    // ============================================================

    @FXML
    private void handleLoadCodes() {

        FileChooser chooser = new FileChooser();
        chooser.setTitle("Select Codes File (CSV or Excel)");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("CSV Files", "*.csv"),
                new FileChooser.ExtensionFilter("Excel Files", "*.xlsx")
        );

        File file = chooser.showOpenDialog(new Stage());
        if (file == null) return;

        try {
            Set<String> loaded = loadCodesFromFile(file);

            if (loaded.isEmpty()) {
                validCodes.clear();
                lastCodesFile = file;
                codesLoaded.set(false);
                updateCodesStatusLabel();

                resultsTextArea.clear();
                exportButton.setDisable(true);

                showInfo("Load Codes", "No codes found.",
                        "Make sure your file contains a column named 'source_value' (or 'source value').");
                setStatus("Codes not loaded.");
                return;
            }

            validCodes.clear();
            validCodes.addAll(loaded);
            lastCodesFile = file;
            codesLoaded.set(true);

            // reset outputs + highlighting
            resultsTextArea.clear();
            exportButton.setDisable(true);
            for (FileItem item : fileItems) item.setHasErrors(false);
            tableView.refresh();

            updateCodesStatusLabel();

            showInfo("Load Codes", "Codes loaded successfully.",
                    "Loaded " + validCodes.size() + " codes from column: source_value");

            setStatus("Codes loaded. Ready to analyze.");

        } catch (Exception e) {
            e.printStackTrace();
            codesLoaded.set(false);
            updateCodesStatusLabel();
            showError("Load Codes", "Failed to load codes file.", e.getMessage());
            setStatus("Failed to load codes.");
        }
    }

    private Set<String> loadCodesFromFile(File file) throws Exception {
        String name = file.getName().toLowerCase(Locale.ROOT);
        if (name.endsWith(".csv")) return loadCodesFromCsv(file);
        if (name.endsWith(".xlsx")) return loadCodesFromXlsx(file);
        throw new IllegalArgumentException("Unsupported file type: " + file.getName());
    }

    private String normalizeCode(String s) {
        if (s == null) return "";
        return s.trim().toUpperCase(Locale.ROOT);
    }

    private String normHeader(String s) {
        if (s == null) return "";
        return s.trim()
                .replace("\"", "")
                .toLowerCase(Locale.ROOT)
                .replace("_", " ");
    }

    private Set<String> loadCodesFromCsv(File file) throws IOException {
        Set<String> codes = new HashSet<>();

        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(file)))) {

            String headerLine = br.readLine();
            if (headerLine == null) return codes;

            String[] headers = headerLine.split(",", -1);
            int sourceIdx = -1;

            for (int i = 0; i < headers.length; i++) {
                String h = normHeader(headers[i]);
                if ("source value".equals(h)) {
                    sourceIdx = i;
                    break;
                }
            }

            if (sourceIdx < 0) return codes;

            String line;
            while ((line = br.readLine()) != null) {
                String[] cols = line.split(",", -1);
                if (cols.length <= sourceIdx) continue;

                String val = cols[sourceIdx].replace("\"", "");
                val = normalizeCode(val);
                if (!val.isBlank()) codes.add(val);
            }
        }

        return codes;
    }

    private Set<String> loadCodesFromXlsx(File file) throws IOException {
        Set<String> codes = new HashSet<>();

        try (FileInputStream fis = new FileInputStream(file);
             XSSFWorkbook wb = new XSSFWorkbook(fis)) {

            Sheet sheet = wb.getNumberOfSheets() > 0 ? wb.getSheetAt(0) : null;
            if (sheet == null) return codes;

            Row header = sheet.getRow(sheet.getFirstRowNum());
            if (header == null) return codes;

            int sourceIdx = -1;
            for (int c = 0; c < header.getLastCellNum(); c++) {
                org.apache.poi.ss.usermodel.Cell cell = header.getCell(c); // POI Cell (fully qualified)
                if (cell == null) continue;
                String h = normHeader(cell.toString());
                if ("source value".equals(h)) {
                    sourceIdx = c;
                    break;
                }
            }

            if (sourceIdx < 0) return codes;

            int firstDataRow = sheet.getFirstRowNum() + 1;
            int lastRow = sheet.getLastRowNum();

            for (int r = firstDataRow; r <= lastRow; r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;

                org.apache.poi.ss.usermodel.Cell cell = row.getCell(sourceIdx); // POI Cell (fully qualified)
                if (cell == null) continue;

                String val = normalizeCode(cell.toString());
                if (!val.isBlank()) codes.add(val);
            }
        }

        return codes;
    }

    private void updateCodesStatusLabel() {
        if (codesStatusLabel == null) return;

        if (validCodes.isEmpty()) {
            codesStatusLabel.setText("Codes: Not loaded (expects column source_value)");
        } else {
            String name = (lastCodesFile == null) ? "" : " (" + lastCodesFile.getName() + ")";
            codesStatusLabel.setText("Codes: Loaded " + validCodes.size() + name);
        }
    }

    // ============================================================
    // Upload / Parsing (ZIP/TXT)
    // ============================================================

    @FXML
    private void handleUpload() {
        fileItems.clear();
        tableView.getItems().clear();

        resultsTextArea.clear();
        exportButton.setDisable(true);

        progressBar.progressProperty().unbind();
        progressBar.setProgress(0);
        progressLabel.setText("0 / 0");
        setProgressDetail("—");

        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Select ZIP or TXT Files");
        fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("ZIP & TXT Files", "*.zip", "*.txt")
        );

        List<File> selectedFiles = fileChooser.showOpenMultipleDialog(new Stage());
        if (selectedFiles == null || selectedFiles.isEmpty()) return;

        setStatus("Uploading and parsing...");

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

                    Platform.runLater(() -> progressLabel.setText("Processing 0 of " + totalTxt));
                    updateProgress(0, Math.max(totalTxt, 1));

                    for (FileOrZipEntry entry : allTxtFiles) {
                        if (entry.zipInputStream != null) processTxtFile(entry.name, entry.zipInputStream);
                        else {
                            try (FileInputStream fis = new FileInputStream(entry.file)) {
                                processTxtFile(entry.name, fis);
                            }
                        }

                        processed++;
                        int current = processed;
                        Platform.runLater(() -> {
                            progressLabel.setText("Processing " + current + " of " + totalTxt);
                            setProgressDetail(entry.name);
                        });
                        updateProgress(processed, Math.max(totalTxt, 1));
                    }

                    Platform.runLater(() -> {
                        setStatus("Upload complete. Ready to analyze.");
                        setProgressDetail("Done");
                        updateCounts(fileItems.size(), 0, 0);
                    });

                } catch (IOException e) {
                    e.printStackTrace();
                    Platform.runLater(() -> {
                        showError("Upload", "Failed to process files.", e.getMessage());
                        setStatus("Upload failed.");
                        setProgressDetail("Error");
                    });
                }
                return null;
            }
        };

        progressBar.progressProperty().bind(task.progressProperty());
        new Thread(task, "upload-task").start();
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
                    byte[] buffer = new byte[4096];
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

    private static class SelectedNad {
        final String qual; // ZZZ, HI, TB
        final String code; // code
        SelectedNad(String qual, String code) {
            this.qual = qual;
            this.code = code;
        }
    }

    private SelectedNad selectNadForUnh02(String unh02, Map<String, String> nadByQual) {
        String u = (unh02 == null) ? "" : unh02.trim().toUpperCase(Locale.ROOT);

        if ("IFTSTA".equals(u) || "IFTMBC".equals(u)) {
            String z = nadByQual.get("ZZZ");
            if (z != null && !z.isBlank()) return new SelectedNad("ZZZ", z);
            return null;
        }

        if ("APERAK".equals(u)) {
            String z = nadByQual.get("ZZZ");
            if (z != null && !z.isBlank()) return new SelectedNad("ZZZ", z);

            String hi = nadByQual.get("HI");
            if (hi != null && !hi.isBlank()) return new SelectedNad("HI", hi);

            String tb = nadByQual.get("TB");
            if (tb != null && !tb.isBlank()) return new SelectedNad("TB", tb);

            return null;
        }

        String z = nadByQual.get("ZZZ");
        if (z != null && !z.isBlank()) return new SelectedNad("ZZZ", z);
        return null;
    }

    private void processTxtFile(String filename, InputStream inputStream) {

        int unhCount = 0;
        int nadCount = 0;

        List<String> outputLines = new ArrayList<>();
        Set<String> unh02Set = new LinkedHashSet<>();

        String tid = filename.toLowerCase(Locale.ROOT).endsWith(".txt")
                ? filename.substring(0, filename.length() - 4)
                : filename;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {

            String line;

            boolean inUnh = false;
            String currentUNH02 = "";
            String currentBGM = "";
            Map<String, String> currentNadByQual = new HashMap<>();

            while ((line = reader.readLine()) != null) {
                String[] segments = line.split("'");

                for (String segment : segments) {
                    segment = segment.trim();
                    if (segment.isEmpty()) continue;

                    if (segment.startsWith("UNH+")) {

                        if (inUnh) {
                            SelectedNad sel = selectNadForUnh02(currentUNH02, currentNadByQual);
                            String nadPart = (sel == null) ? "NO_NAD" : (sel.qual + "=" + sel.code);
                            String bgmPart = (currentBGM == null || currentBGM.isBlank()) ? "No BGM02" : currentBGM;

                            outputLines.add(currentUNH02 + "|" + bgmPart + "/" + nadPart);
                        }

                        inUnh = true;
                        unhCount++;

                        currentBGM = "";
                        currentNadByQual.clear();

                        String[] parts = segment.split("\\+");
                        String msgType = "";
                        if (parts.length > 2) {
                            msgType = parts[2].trim();
                            int colon = msgType.indexOf(':');
                            if (colon >= 0) msgType = msgType.substring(0, colon).trim();
                        }
                        currentUNH02 = normalizeCode(msgType);
                        if (!currentUNH02.isBlank()) unh02Set.add(currentUNH02);

                        continue;
                    }

                    if (!inUnh) continue;

                    if (segment.startsWith("BGM+")) {
                        String[] parts = segment.split("\\+");
                        if (parts.length > 2) currentBGM = parts[2].trim();
                        continue;
                    }

                    if (segment.startsWith("NAD+")) {
                        String[] parts = segment.split("\\+");
                        if (parts.length >= 3) {
                            String qual = normalizeCode(parts[1]); // ZZZ/HI/TB
                            if (!("ZZZ".equals(qual) || "HI".equals(qual) || "TB".equals(qual))) continue;

                            String nadValue = parts[2].trim();

                            int underscore = nadValue.indexOf('_');
                            if (underscore >= 0) nadValue = nadValue.substring(underscore + 1).trim();

                            int colon = nadValue.indexOf(':');
                            if (colon >= 0) nadValue = nadValue.substring(0, colon).trim();

                            nadValue = normalizeCode(nadValue);

                            if (!nadValue.isBlank()) {
                                currentNadByQual.put(qual, nadValue);
                                nadCount++;
                            }
                        }
                    }
                }
            }

            if (inUnh) {
                SelectedNad sel = selectNadForUnh02(currentUNH02, currentNadByQual);
                String nadPart = (sel == null) ? "NO_NAD" : (sel.qual + "=" + sel.code);
                String bgmPart = (currentBGM == null || currentBGM.isBlank()) ? "No BGM02" : currentBGM;

                outputLines.add(currentUNH02 + "|" + bgmPart + "/" + nadPart);
            }

        } catch (IOException e) {
            e.printStackTrace();
        }

        final String documentIDs = String.join("\n", outputLines);
        final String finalFilename = filename;
        final String finalTid = tid;

        String unh02Summary = unh02Set.isEmpty() ? "" : " [" + String.join(", ", unh02Set) + "]";
        final String finalUnhCount = "UNH: " + unhCount + unh02Summary + " / NAD: " + nadCount;

        Platform.runLater(() ->
                fileItems.add(new FileItem(finalFilename, finalTid, finalUnhCount, documentIDs))
        );
    }

    // ============================================================
    // Analyze
    // ============================================================

    private String formatReferenceLine(String documentID, String unh02) {
        String nadRule;
        if ("IFTSTA".equals(unh02) || "IFTMBC".equals(unh02)) {
            nadRule = "NAD+ZZZ";
        } else if ("APERAK".equals(unh02)) {
            nadRule = "NAD+HI/TB/ZZZ";
        } else {
            nadRule = "NAD";
        }
        return documentID + " - " + unh02 + " - " + nadRule;
    }

    private String buildErrorSummaryForItem(FileItem item) {

        List<String> missingRequiredNadLines = new ArrayList<>();
        Map<String, List<String>> missingCodes = new LinkedHashMap<>();

        String doc = item.getDocumentID();
        if (doc == null || doc.isBlank()) return "";

        for (String rawLine : doc.split("\\R")) {

            if (rawLine == null || rawLine.isBlank()) continue;

            String[] p1 = rawLine.split("\\|", 2);
            if (p1.length < 2) continue;

            String unh02 = normalizeCode(p1[0]);
            String rest = p1[1];

            String[] p2 = rest.split("/", 2);
            if (p2.length < 2) continue;

            String bgm = p2[0].trim();
            String nadPart = p2[1].trim();

            if ("NO_NAD".equalsIgnoreCase(nadPart)) {
                missingRequiredNadLines.add(formatReferenceLine(bgm, unh02));
                continue;
            }

            String[] qc = nadPart.split("=", 2);
            if (qc.length < 2) continue;

            String qual = normalizeCode(qc[0]);
            String code = normalizeCode(qc[1]);

            if (code.isBlank()) {
                missingRequiredNadLines.add(formatReferenceLine(bgm, unh02));
                continue;
            }

            if (!validCodes.contains(code)) {
                missingCodes
                        .computeIfAbsent(code, k -> new ArrayList<>())
                        .add(formatReferenceLine(bgm, unh02) + " (NAD+" + qual + ")");
            }
        }

        StringBuilder sb = new StringBuilder();

        for (Map.Entry<String, List<String>> entry : missingCodes.entrySet()) {
            sb.append("Error: Code does not exist in Customer Code table (")
                    .append(entry.getKey())
                    .append(")\n");
            sb.append("Reference Number:\n");
            for (String line : entry.getValue()) sb.append(line).append("\n");
            sb.append("\n");
        }

        if (!missingRequiredNadLines.isEmpty()) {
            sb.append("Error: Missing required NAD\n");
            sb.append("Reference Number:\n");
            for (String line : missingRequiredNadLines) sb.append(line).append("\n");
            sb.append("\n");
        }

        return sb.toString().trim();
    }

    @FXML
    private void checkAspectInCollection() {

        if (validCodes.isEmpty()) {
            showInfo("Analyze Errors", "Codes not loaded",
                    "Please click 'Load Codes (CSV/XLSX)' and select a file with column 'source_value'.");
            return;
        }

        resultsTextArea.clear();
        exportButton.setDisable(true);

        for (FileItem item : fileItems) item.setHasErrors(false);

        StringBuilder errorDetails = new StringBuilder();
        boolean errorsFound = false;

        int totalFiles = fileItems.size();
        int tidsWithErrors = 0;
        int totalErrorCount = 0;

        for (FileItem item : fileItems) {

            String itemSummary = buildErrorSummaryForItem(item);

            if (!itemSummary.isBlank()) {
                item.setHasErrors(true);
                errorsFound = true;
                tidsWithErrors++;

                for (String l : itemSummary.split("\\R")) {
                    if (l.startsWith("Error:")) totalErrorCount++;
                }

                errorDetails.append("ERRORS!\nTID: ")
                        .append(item.getTid())
                        .append("\n")
                        .append(itemSummary)
                        .append("\n\n");
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
                    .append("All TIDs passed NAD lookup checks (with UNH02 rules).\n");
            resultsTextArea.setText(finalOutput.toString());
            exportButton.setDisable(true);
            setStatus("Analysis complete: no errors detected.");
        } else {
            finalOutput.append(errorDetails);
            resultsTextArea.setText(finalOutput.toString());
            exportButton.setDisable(false);
            setStatus("Analysis complete: errors found.");
        }

        updateCounts(totalFiles, tidsWithErrors, totalErrorCount);
        setProgressDetail("Done");
    }

    // ============================================================
    // Export to Excel (POI Cell + Hyperlink fully qualified)
    // ============================================================

    @FXML
    private void exportToExcel() {
        if (fileItems.isEmpty()) {
            showInfo("Export Excel", "No data to export!", "Upload files and run analysis first.");
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
            header.createCell(2).setCellValue("UNH/NAD Count (with UNH02)");
            header.createCell(3).setCellValue("UNH02 | Reference Number / NADQUAL=CODE");
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

                org.apache.poi.ss.usermodel.Cell tidCell = row.createCell(1); // POI Cell
                tidCell.setCellValue(item.getTid());

                String tidForUrl = sanitizeTidForUrl(item.getTid());
                if (tidForUrl != null && !tidForUrl.isBlank()) {
                    org.apache.poi.ss.usermodel.Hyperlink tidHyperlink =
                            creationHelper.createHyperlink(org.apache.poi.common.usermodel.HyperlinkType.URL);

                    String url = "https://www.myvan.descartes.com/DocTrackingCore/Document/RetrieveTidStory?tid="
                            + urlEncode(tidForUrl);

                    tidHyperlink.setAddress(url);
                    tidCell.setHyperlink(tidHyperlink);
                    tidCell.setCellStyle(hlinkStyle);
                }

                row.createCell(2).setCellValue(item.getUnhCount());

                org.apache.poi.ss.usermodel.Cell docCell = row.createCell(3);
                docCell.setCellValue(item.getDocumentID() == null ? "" : item.getDocumentID());
                docCell.setCellStyle(wrapStyle);

                org.apache.poi.ss.usermodel.Cell summaryCell = row.createCell(4);
                String summary = buildErrorSummaryForItem(item);
                summaryCell.setCellValue(summary);
                summaryCell.setCellStyle(wrapStyle);

                row.setHeight((short) -1);
            }

            for (int i = 0; i <= 4; i++) sheet.autoSizeColumn(i);

            try (FileOutputStream fos = new FileOutputStream(file)) {
                workbook.write(fos);
            }

            showInfo("Export Excel", "Export successful", "Excel exported successfully with clickable TIDs!");
            setStatus("Export complete.");

        } catch (Exception e) {
            e.printStackTrace();
            showError("Export Excel", "Error exporting Excel", e.getMessage());
            setStatus("Export failed.");
        }
    }

    // ============================================================
    // Alerts
    // ============================================================

    private void showInfo(String title, String header, String msg) {
        Alert a = new Alert(Alert.AlertType.INFORMATION);
        a.setTitle(title);
        a.setHeaderText(header);
        a.setContentText(msg);
        a.showAndWait();
    }

    private void showError(String title, String header, String msg) {
        Alert a = new Alert(Alert.AlertType.ERROR);
        a.setTitle(title);
        a.setHeaderText(header);
        a.setContentText(msg);
        a.showAndWait();
    }

    // ============================================================
    // FileItem
    // ============================================================

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