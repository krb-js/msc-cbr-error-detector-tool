MSC CBR Error Detector Tool

🚀 Overview
The MSC CBR Error Detector Tool is a JavaFX desktop application designed to automate the detection of NAD+ZZZ errors in MSC CBR EDI files.
This tool was built to eliminate manual error hunting, reduce investigation time from hours to seconds, and improve accuracy when handling high-volume support tickets.

🎯 Problem It Solves
When processing MSC CBR files:
NAD+ZZZ segments may be missing
Customer codes may not exist in the MSC code table
A single TXT file may contain dozens of documents
Manually locating the affected documentID is slow and error-prone
This tool automates detection and pinpoints exactly which document triggers the issue.

✨ Features
🔍 Automatic NAD+ZZZ detection across all documents
📄 Document-level error pinpointing
📦 Batch processing for ZIP and TXT files
📊 Excel export with formatted error summaries
🔗 Clickable TIDs in generated reports
💻 Portable version available (no installation required)

🛠 Tech Stack
Java 21
JavaFX 21
Maven
Apache POI (Excel generation)
jlink + jpackage (custom runtime & distribution)

📦 Portable Version
The portable build requires no installation.

How to run:
Extract the ZIP file completely
Open the extracted folder
Double-click MSCCBRErrorDetectorTool.exe

⚠️ Important:
Do not move the .exe file outside its folder. It must stay beside the runtime and app directories.

🏗 Build Instructions (For Developers)
1️⃣ Build the project
mvnw.cmd clean package
2️⃣ Create custom runtime (jlink)
jlink --module-path "<jdk>/jmods;<javafx>/jmods" \
      --add-modules javafx.controls,javafx.fxml \
      --output build/runtime
3️⃣ Package portable app-image
jpackage --type app-image \
         --runtime-image build/runtime \
         --input target \
         --main-jar demo-1.0-SNAPSHOT-all.jar \
         --main-class com.example.demo.Launcher \
         --dest dist

🧠 Why This Project Exists
As the support engineer handling MSC CBR tickets, I encountered recurring NAD+ZZZ errors that required manual document scanning.
Instead of accepting repetitive manual investigation, I built an internal automation tool to:
Reduce handling time
Improve reporting accuracy
Minimize human error
Increase team efficiency
What used to take hours now takes seconds.


👤 Author
krb.js

📄 License
Internal tool / Portfolio showcase.
