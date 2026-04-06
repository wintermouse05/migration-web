package org.example.migrationdbweb.migratetool;




import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JProgressBar;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.UnsupportedLookAndFeelException;
import javax.swing.border.TitledBorder;

public class MigrationAppUI extends JFrame {

    // --- UI Fields: Source DB ---
    private JComboBox<String> sourceDbTypeCombo;
    private JTextField sourceHostField, sourcePortField, sourceDbNameField, sourceUserField;
    private JPasswordField sourcePassField;
    private JTextField sourceSchemaField;

    // --- UI Fields: Target DB ---
    private JComboBox<String> targetDbTypeCombo;
    private JTextField targetHostField, targetPortField, targetDbNameField, targetUserField;
    private JPasswordField targetPassField;
    private JTextField targetSchemaField;

    // --- UI Fields: Migration mode ---
    private JRadioButton optCopyAll, optStructureOnly, optDataOnly;
    private JCheckBox chkTruncateTarget, chkCopyNewOnly;
    private JTextField limitDataField, batchSizeField;
    private JTextField dataThreadsField;
    private JTextField includeTablesField, excludeTablesField;

    // --- UI Fields: Advanced objects ---
    private JCheckBox chkMigrateSequences, chkMigrateIndexes;
    private JCheckBox chkMigrateFunctions, chkMigrateTriggers;
    private JCheckBox chkMigrateViews, chkReplaceExistingViews;
    private JTextField includeViewsField, excludeViewsField;

    // --- UI Fields: Retry ---
    private JCheckBox chkRetryEnabled;
    private JTextField retryMaxAttemptsField, retryDelayMsField, retryBackoffField;

    // --- UI Fields: Resume ---
    private JCheckBox chkResumeEnabled;
    private JTextField resumeStateFileField;
    private JCheckBox chkResumeReset;

    // --- Actions ---
    private JButton btnStartMigration;
    private JTextArea logArea;
    private JProgressBar progressBar;

    // --- Saved credentials (JSON local file) ---
    private JComboBox<String> sourceCredentialCombo;
    private JComboBox<String> targetCredentialCombo;
    private final SavedCredentialJsonStore credentialStore = new SavedCredentialJsonStore();
    private final Map<String, SavedCredentialJsonStore.SavedDbCredential> credentialByName = new LinkedHashMap<>();

    public MigrationAppUI() {
        setTitle("Database Migration Tool");
        setSize(1050, 820);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout(10, 10));
        initComponents();
    }

    private void initComponents() {
        // NORTH: DB config panels
        JPanel dbConfigPanel = new JPanel(new GridLayout(1, 2, 10, 0));
        dbConfigPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        dbConfigPanel.add(createDbConfigPanel("Source Database (Nguồn)", true));
        dbConfigPanel.add(createDbConfigPanel("Target Database (Đích)", false));
        add(dbConfigPanel, BorderLayout.NORTH);

        // CENTER: Options panel
        JPanel optionsPanel = createOptionsPanel();
        add(optionsPanel, BorderLayout.CENTER);

        // SOUTH: Progress + Logs + Action button
        JPanel bottomPanel = createBottomPanel();
        add(bottomPanel, BorderLayout.SOUTH);

        bindOptionStateListeners();
        updateModeDependentOptionsState();

        refreshSavedCredentialsCombos();
    }

    private void bindOptionStateListeners() {
        if (optCopyAll != null) {
            optCopyAll.addActionListener(e -> updateModeDependentOptionsState());
        }
        if (optStructureOnly != null) {
            optStructureOnly.addActionListener(e -> updateModeDependentOptionsState());
        }
        if (optDataOnly != null) {
            optDataOnly.addActionListener(e -> updateModeDependentOptionsState());
        }
        if (chkMigrateViews != null) {
            chkMigrateViews.addActionListener(e -> updateModeDependentOptionsState());
        }
    }

    private void updateModeDependentOptionsState() {
        boolean structureOnly = optStructureOnly != null && optStructureOnly.isSelected();
        boolean dataOnly = optDataOnly != null && optDataOnly.isSelected();

        if (chkTruncateTarget != null) {
            chkTruncateTarget.setEnabled(!structureOnly);
        }
        if (chkCopyNewOnly != null) {
            chkCopyNewOnly.setEnabled(!structureOnly);
        }

        boolean allowDdlOptions = !dataOnly;
        if (chkMigrateSequences != null) {
            chkMigrateSequences.setEnabled(allowDdlOptions);
        }
        if (chkMigrateIndexes != null) {
            chkMigrateIndexes.setEnabled(allowDdlOptions);
        }
        if (chkMigrateFunctions != null) {
            chkMigrateFunctions.setEnabled(allowDdlOptions);
        }
        if (chkMigrateTriggers != null) {
            chkMigrateTriggers.setEnabled(allowDdlOptions);
        }
        if (chkMigrateViews != null) {
            chkMigrateViews.setEnabled(allowDdlOptions);
        }

        boolean allowViewDetails = allowDdlOptions
                && chkMigrateViews != null
                && chkMigrateViews.isSelected();
        if (chkReplaceExistingViews != null) {
            chkReplaceExistingViews.setEnabled(allowViewDetails);
        }
        if (includeViewsField != null) {
            includeViewsField.setEnabled(allowViewDetails);
        }
        if (excludeViewsField != null) {
            excludeViewsField.setEnabled(allowViewDetails);
        }
    }

    /**
     * Tạo panel cấu hình kết nối DB (Source hoặc Target).
     * Thêm trường Schema override.
     */
    private JPanel createDbConfigPanel(String title, boolean isSource) {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(), title, TitledBorder.LEFT, TitledBorder.TOP));

        JComboBox<String> dbTypeCombo = new JComboBox<>(new String[]{"Oracle", "PostgreSQL"});
        JTextField hostField = new JTextField(isSource ? "localhost" : "127.0.0.1");
        JTextField portField = new JTextField(isSource ? "1521" : "5432");
        JTextField dbNameField = new JTextField(isSource ? "ORCL" : "migration_db");
        JTextField userField = new JTextField(isSource ? "scott" : "postgres");
        JPasswordField passField = new JPasswordField();
        JTextField schemaField = new JTextField(isSource ? "" : "public");

        if (isSource) {
            sourceDbTypeCombo = dbTypeCombo; sourceHostField = hostField;
            sourcePortField = portField; sourceDbNameField = dbNameField;
            sourceUserField = userField; sourcePassField = passField;
            sourceSchemaField = schemaField;
        } else {
            targetDbTypeCombo = dbTypeCombo; targetHostField = hostField;
            targetPortField = portField; targetDbNameField = dbNameField;
            targetUserField = userField; targetPassField = passField;
            targetSchemaField = schemaField;
        }

        // Grid 7 rows: type, host, port, dbname, user, pass, schema
        JPanel formPanel = new JPanel(new GridLayout(7, 1, 5, 5));
        formPanel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        // Row: Database Type
        formPanel.add(makeLabeledRow("Database Type:", dbTypeCombo));

        // Row: Host
        formPanel.add(makeLabeledRow("Host:", hostField));

        // Row: Port
        formPanel.add(makeLabeledRow("Port:", portField));

        // Row: DB Name / SID
        formPanel.add(makeLabeledRow("DB Name / SID:", dbNameField));

        // Row: Username
        formPanel.add(makeLabeledRow("Username:", userField));

        // Row: Password
        formPanel.add(makeLabeledRow("Password:", passField));

        // Row: Schema — đặt preferred width để field rộng, dễ nhập
        schemaField.setPreferredSize(new Dimension(300, 22));
        schemaField.setToolTipText("Bo trong = dung schema mac dinh (Oracle: username.uppercase, PG: public)");
        formPanel.add(makeLabeledRow("Schema (override):", schemaField));

        panel.add(formPanel, BorderLayout.CENTER);

        JButton btnTest = new JButton("Test Connection");
        btnTest.addActionListener(e -> testConnectionAction(isSource));
        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JComboBox<String> credentialCombo = new JComboBox<>();
        credentialCombo.setPrototypeDisplayValue("credential-name-xxxxxxxxxxxx");
        JButton btnLoadCredential = new JButton("Load Credential");
        JButton btnSaveCredential = new JButton("Save Credential");

        if (isSource) {
            sourceCredentialCombo = credentialCombo;
        } else {
            targetCredentialCombo = credentialCombo;
        }

        btnLoadCredential.addActionListener(e -> loadCredentialAction(isSource));
        btnSaveCredential.addActionListener(e -> saveCredentialAction(isSource));

        btnPanel.add(new JLabel("Credential:"));
        btnPanel.add(credentialCombo);
        btnPanel.add(btnLoadCredential);
        btnPanel.add(btnSaveCredential);
        btnPanel.add(btnTest);
        panel.add(btnPanel, BorderLayout.SOUTH);

        return panel;
    }

    /**
     * Tạo panel tùy chọn migration — chia thành các section rõ ràng.
     */
    private JPanel createOptionsPanel() {
        JPanel wrapper = new JPanel(new BorderLayout(10, 8));
        wrapper.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JPanel grid = new JPanel(new GridLayout(3, 2, 10, 10));
        grid.add(createSection1ModePanel());
        grid.add(createSection2CoreOptionsPanel());
        grid.add(createSection3AdvancedPanel());
        grid.add(createSection4ViewsPanel());
        grid.add(createSection5RetryPanel());
        grid.add(createSection6ResumePanel());
        wrapper.add(grid, BorderLayout.CENTER);

        JPanel notePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 2));
        JLabel note = new JLabel("Neu tien trinh bi treo hon 5 phut ma khong co log moi, vui long kiem tra ket noi DB.");
        note.setFont(new Font("Arial", Font.ITALIC, 11));
        note.setForeground(new Color(120, 100, 80));
        notePanel.add(note);
        wrapper.add(notePanel, BorderLayout.SOUTH);

        return wrapper;
    }

    private JPanel createSection1ModePanel() {
        JPanel section = createOptionSection("1. CHE DO MIGRATION");
        JPanel content = new JPanel(new GridLayout(3, 1, 4, 4));

        optCopyAll = new JRadioButton("Copy cau truc va du lieu", true);
        optStructureOnly = new JRadioButton("Chi copy cau truc (DDL)");
        optDataOnly = new JRadioButton("Chi copy du lieu (DML)");

        ButtonGroup modeGroup = new ButtonGroup();
        modeGroup.add(optCopyAll);
        modeGroup.add(optStructureOnly);
        modeGroup.add(optDataOnly);

        content.add(optCopyAll);
        content.add(optStructureOnly);
        content.add(optDataOnly);
        section.add(content, BorderLayout.CENTER);
        return section;
    }

    private JPanel createSection2CoreOptionsPanel() {
        JPanel section = createOptionSection("2. TUY CHON CO BAN");
        JPanel content = new JPanel(new GridLayout(5, 1, 4, 4));

        chkTruncateTarget = new JCheckBox("Xoa du lieu cu target truoc khi migrate (TRUNCATE)");
        chkCopyNewOnly = new JCheckBox("Chi copy ban ghi moi (theo PK — bo qua trung lap)");
        content.add(chkTruncateTarget);
        content.add(chkCopyNewOnly);

        JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        row2.add(new JLabel("Batch size (rows/batch):"));
        batchSizeField = new JTextField("1000", 7);
        batchSizeField.setToolTipText("So dong migrate trong mot batch. Tang de toc do, giam de tranh tran bo nho.");
        row2.add(batchSizeField);
        row2.add(Box.createHorizontalStrut(20));
        row2.add(new JLabel("Limit/bang (0 = tat ca):"));
        limitDataField = new JTextField("0", 7);
        limitDataField.setToolTipText("Nhap 0 de copy toan bo dong.");
        row2.add(limitDataField);
        row2.add(Box.createHorizontalStrut(20));
        row2.add(new JLabel("Data threads (0 = auto):"));
        dataThreadsField = new JTextField("0", 5);
        dataThreadsField.setToolTipText("So luong thread cho phase migrate data. 0 = tu dong theo pool/table.");
        row2.add(dataThreadsField);
        content.add(row2);

        JPanel includePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 2));
        includePanel.add(new JLabel("Include bang (CSV):"));
        includeTablesField = new JTextField("", 28);
        includeTablesField.setToolTipText("Ho tro wildcard * (vi du: user*,order_*). Bo trong = migrate tat ca.");
        includePanel.add(includeTablesField);
        content.add(includePanel);

        JPanel excludePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 2));
        excludePanel.add(new JLabel("Exclude bang  (CSV):"));
        excludeTablesField = new JTextField("", 28);
        excludeTablesField.setToolTipText("Ho tro wildcard * (vi du: temp_*,audit*).");
        excludePanel.add(excludeTablesField);
        content.add(excludePanel);

        section.add(content, BorderLayout.CENTER);
        return section;
    }

    private JPanel createSection3AdvancedPanel() {
        JPanel section = createOptionSection("3. DOI TUONG NANG CAO");
        JPanel content = new JPanel(new GridLayout(2, 1, 4, 4));

        chkMigrateSequences = new JCheckBox("Sequences");
        chkMigrateIndexes   = new JCheckBox("Indexes");
        chkMigrateFunctions = new JCheckBox("Functions/Procedures");
        chkMigrateTriggers = new JCheckBox("Triggers");

        JPanel advRow1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        advRow1.add(chkMigrateSequences);
        advRow1.add(chkMigrateIndexes);
        content.add(advRow1);

        JPanel advRow2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        advRow2.add(chkMigrateFunctions);
        advRow2.add(chkMigrateTriggers);
        content.add(advRow2);

        section.add(content, BorderLayout.CENTER);
        return section;
    }

    private JPanel createSection4ViewsPanel() {
        JPanel section = createOptionSection("4. VIEWS");
        JPanel content = new JPanel(new GridLayout(4, 1, 4, 4));

        chkMigrateViews = new JCheckBox("Migrate views");
        chkReplaceExistingViews = new JCheckBox("Thay the views da ton tai (DROP + CREATE)");
        content.add(chkMigrateViews);
        content.add(chkReplaceExistingViews);

        JPanel includeViewsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 2));
        includeViewsPanel.add(new JLabel("  Include views (CSV):"));
        includeViewsField = new JTextField("", 28);
        includeViewsField.setToolTipText("Ho tro wildcard * (vi du: v_user*,v_order_*).");
        includeViewsPanel.add(includeViewsField);
        content.add(includeViewsPanel);

        JPanel excludeViewsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 2));
        excludeViewsPanel.add(new JLabel("  Exclude views  (CSV):"));
        excludeViewsField = new JTextField("", 28);
        excludeViewsField.setToolTipText("Ho tro wildcard * (vi du: v_temp*).");
        excludeViewsPanel.add(excludeViewsField);
        content.add(excludeViewsPanel);

        section.add(content, BorderLayout.CENTER);
        return section;
    }

    private JPanel createSection5RetryPanel() {
        JPanel section = createOptionSection("5. RETRY / TU DONG THU LAI");
        JPanel content = new JPanel(new GridLayout(2, 1, 4, 4));

        chkRetryEnabled = new JCheckBox("Bat dau tinh nang retry khi gap loi tam thoi");
        content.add(chkRetryEnabled);

        JPanel retryRow1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        retryRow1.add(new JLabel("So lan retry toi da:"));
        retryMaxAttemptsField = new JTextField("3", 5);
        retryRow1.add(retryMaxAttemptsField);
        retryRow1.add(Box.createHorizontalStrut(10));
        retryRow1.add(new JLabel("Delay ban dau (ms):"));
        retryDelayMsField = new JTextField("2000", 7);
        retryRow1.add(retryDelayMsField);
        retryRow1.add(Box.createHorizontalStrut(10));
        retryRow1.add(new JLabel("Backoff multiplier:"));
        retryBackoffField = new JTextField("2.0", 5);
        retryRow1.add(retryBackoffField);
        content.add(retryRow1);

        section.add(content, BorderLayout.CENTER);
        return section;
    }

    private JPanel createSection6ResumePanel() {
        JPanel section = createOptionSection("6. RESUME / TIEP TUC TU DIEM DA DUNG");
        JPanel content = new JPanel(new GridLayout(2, 1, 4, 4));

        chkResumeEnabled = new JCheckBox("Bat dau tinh nang resume (tiep tuc tu diem da dung)");
        content.add(chkResumeEnabled);

        JPanel resumeRow1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        resumeRow1.add(new JLabel("File checkpoint:"));
        resumeStateFileField = new JTextField(".migration-resume.properties", 26);
        resumeStateFileField.setToolTipText("Duong dan tuyet doi hoac tuong doi toi file checkpoint.");
        resumeRow1.add(resumeStateFileField);
        this.chkResumeReset = new JCheckBox("Reset checkpoint cu?");
        resumeRow1.add(Box.createHorizontalStrut(10));
        resumeRow1.add(this.chkResumeReset);
        content.add(resumeRow1);

        section.add(content, BorderLayout.CENTER);
        return section;
    }

    private JPanel createOptionSection(String title) {
        JPanel section = new JPanel(new BorderLayout(5, 5));
        section.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(),
                title,
                TitledBorder.LEFT,
                TitledBorder.TOP
        ));
        return section;
    }

    /** SwingUtilities.invokeLater wrapper */
    public void appendLog(String message) {
        SwingUtilities.invokeLater(() -> {
            logArea.append(message + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    /** Enable/disable the Start button */
    public void enableStartButton(boolean enable) {
        SwingUtilities.invokeLater(() -> btnStartMigration.setEnabled(enable));
    }

    /** Kích hoạt khi nhấn Start Migration */
    private void startMigrationAction() {
        // ── 1. Build DatabaseConfig ───────────────────────────────────
        DatabaseConfig sourceConfig;
        DatabaseConfig targetConfig;
        try {
            sourceConfig = buildDatabaseConfig(true);
            targetConfig = buildDatabaseConfig(false);
        } catch (IllegalArgumentException e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), "Loi cau hinh", JOptionPane.ERROR_MESSAGE);
            return;
        }

        // ── 2. Parse simple options ───────────────────────────────────
        boolean isStructureOnly = optStructureOnly.isSelected();
        boolean isDataOnly = optDataOnly.isSelected();
        boolean truncateTarget = chkTruncateTarget.isSelected();
        boolean copyNewOnly = chkCopyNewOnly.isSelected();

        Integer limitRows = parseIntField(limitDataField, 0, "Limit");
        int batchSize = parseIntField(batchSizeField, 1000, "Batch size");
        batchSize = Math.max(1, batchSize);
        int dataThreads = parseIntField(dataThreadsField, 0, "Data threads");
        dataThreads = Math.max(0, dataThreads);

        Set<String> includeTables = parseCsvTableSet(includeTablesField.getText());
        Set<String> excludeTables = parseCsvTableSet(excludeTablesField.getText());
        Set<String> includeViews  = parseCsvTableSet(includeViewsField.getText());
        Set<String> excludeViews  = parseCsvTableSet(excludeViewsField.getText());

        // Schema: dùng override nếu có, không thì dùng default
        String sourceSchema = parseSchema(sourceSchemaField, sourceConfig);
        String targetSchema = parseSchema(targetSchemaField, targetConfig);
        sourceConfig.setSchemaName(sourceSchema);
        targetConfig.setSchemaName(targetSchema);

        // ── 3. Parse Retry policy ───────────────────────────────────────
        boolean retryEnabled = chkRetryEnabled.isSelected();
        int retryMaxAttempts = parseIntField(retryMaxAttemptsField, 3, "Retry max attempts");
        long retryDelayMs = parseLongField(retryDelayMsField, 2000L, "Retry delay ms");
        double retryBackoff = parseDoubleField(retryBackoffField, 2.0, "Backoff multiplier");

        MigrationRetryPolicy retryPolicy = new MigrationRetryPolicy(
                retryEnabled,
                retryMaxAttempts,
                retryDelayMs,
                retryBackoff,
                false, // resumeEnabled — set below
                null,  // resumeStateFile — set below
                false  // resetResumeState — set below
        );

        // ── 4. Parse Resume options ────────────────────────────────────
        boolean resumeEnabled = chkResumeEnabled.isSelected();
        String resumeStateFile = resumeStateFileField.getText();
        boolean resumeReset = chkResumeReset != null && chkResumeReset.isSelected();

        MigrationRetryPolicy effectiveRetryPolicy;
        if (resumeEnabled) {
            effectiveRetryPolicy = new MigrationRetryPolicy(
                    retryEnabled,
                    retryMaxAttempts,
                    retryDelayMs,
                    retryBackoff,
                    true,
                    resumeStateFile,
                    resumeReset
            );
        } else {
            effectiveRetryPolicy = retryPolicy;
        }

        // ── 5. Advanced object flags ──────────────────────────────────
        boolean migrateSequences = chkMigrateSequences.isSelected();
        boolean migrateIndexes   = chkMigrateIndexes.isSelected();
        boolean migrateFunctions = chkMigrateFunctions.isSelected();
        boolean migrateTriggers  = chkMigrateTriggers.isSelected();
        boolean migrateViews      = chkMigrateViews.isSelected();
        boolean replaceExistingViews = chkReplaceExistingViews.isSelected();

        // ── 6. UI lock & clear ───────────────────────────────────────
        enableStartButton(false);
        progressBar.setValue(0);
        logArea.setText("");

        appendLog("=== BAT DAU MIGRATION ===");
        appendLog("Source: " + sourceConfig);
        appendLog("Target: " + targetConfig);
        appendLog("Schema source=" + sourceSchema + " | target=" + targetSchema);
        appendLog("Mode: " + (isStructureOnly ? "STRUCTURE_ONLY" : isDataOnly ? "DATA_ONLY" : "ALL"));
        appendLog("Batch size: " + batchSize + " | Limit: " + (limitRows == null ? "ALL" : limitRows));
        appendLog("Data threads: " + (dataThreads == 0 ? "AUTO" : dataThreads));
        appendLog("Truncate: " + truncateTarget + " | CopyNewOnly: " + copyNewOnly);
        appendLog("Retry: enabled=" + retryEnabled + " attempts=" + retryMaxAttempts
                + " delay=" + retryDelayMs + "ms backoff=" + retryBackoff);
        appendLog("Resume: enabled=" + resumeEnabled + " file=" + (resumeEnabled ? resumeStateFile : "N/A"));
        appendLog("Advanced: seq=" + migrateSequences + " idx=" + migrateIndexes
                + " fn=" + migrateFunctions + " trig=" + migrateTriggers);
        appendLog("Views: migrate=" + migrateViews + " replace=" + replaceExistingViews);
        appendLog("-------------------------------");

        // ── 7. Create and start worker ────────────────────────────────
        MigrationWorker worker = new MigrationWorker(
                this,
                isStructureOnly,
                isDataOnly,
                sourceConfig,
                targetConfig,
                sourceSchema,
                targetSchema,
                batchSize,
                dataThreads,
                truncateTarget,
                copyNewOnly,
                limitRows,
                includeTables,
                excludeTables,
                migrateSequences,
                migrateIndexes,
                migrateFunctions,
                migrateTriggers,
                migrateViews,
                replaceExistingViews,
                includeViews,
                excludeViews,
                effectiveRetryPolicy
        );

        worker.addPropertyChangeListener(evt -> {
            if ("progress".equals(evt.getPropertyName())) {
                int prog = (Integer) evt.getNewValue();
                SwingUtilities.invokeLater(() -> progressBar.setValue(prog));
            }
        });

        worker.execute();
    }

    /** Build DatabaseConfig từ form fields. */
    private DatabaseConfig buildDatabaseConfig(boolean isSource) {
        String selectedType = isSource
                ? (String) sourceDbTypeCombo.getSelectedItem()
                : (String) targetDbTypeCombo.getSelectedItem();

        DatabaseType dbType = parseDatabaseType(selectedType);
        String host = requireText(isSource ? sourceHostField : targetHostField, "Host");
        int port = parsePort(isSource);
        String dbName = requireText(isSource ? sourceDbNameField : targetDbNameField, "DB Name / SID");
        String username = requireText(isSource ? sourceUserField : targetUserField, "Username");
        String password = new String(isSource ? sourcePassField.getPassword() : targetPassField.getPassword());
        DatabaseConfig config = new DatabaseConfig(dbType, host, port, dbName, username, password);
        JTextField schemaField = isSource ? sourceSchemaField : targetSchemaField;
        if (schemaField != null) {
            String rawSchema = schemaField.getText();
            if (rawSchema != null && !rawSchema.trim().isEmpty()) {
                config.setSchemaName(rawSchema.trim());
            }
        }
        return config;
    }

    /** Schema: dùng field override nếu filled, không thì default logic. */
    private String parseSchema(JTextField schemaField, DatabaseConfig config) {
        String override = schemaField.getText();
        if (override != null && !override.trim().isBlank()) {
            return override.trim();
        }
        // Default schema
        if (config.getType() == DatabaseType.ORACLE) {
            return config.getUsername().toUpperCase(Locale.ROOT);
        }
        return "public";
    }

    private DatabaseType parseDatabaseType(String selected) {
        if ("Oracle".equalsIgnoreCase(selected)) return DatabaseType.ORACLE;
        if ("PostgreSQL".equalsIgnoreCase(selected)) return DatabaseType.POSTGRESQL;
        throw new IllegalArgumentException("Database Type khong hop le: " + selected);
    }

    private String requireText(JTextField field, String label) {
        String v = field.getText();
        if (v == null || v.isBlank()) throw new IllegalArgumentException(label + " khong duoc de trong.");
        return v.trim();
    }

    private int parsePort(boolean isSource) {
        String raw = (isSource ? sourcePortField : targetPortField).getText();
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Port khong hop le: " + raw);
        }
    }

    private Integer parseIntField(JTextField field, int defaultVal, String label) {
        String raw = field.getText();
        if (raw == null || raw.isBlank()) return defaultVal;
        try {
            int v = Integer.parseInt(raw.trim());
            if (v < 0) throw new IllegalArgumentException(label + " phai >= 0.");
            return v;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(label + " khong hop le: " + raw);
        }
    }

    private long parseLongField(JTextField field, long defaultVal, String label) {
        String raw = field.getText();
        if (raw == null || raw.isBlank()) return defaultVal;
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(label + " khong hop le: " + raw);
        }
    }

    private double parseDoubleField(JTextField field, double defaultVal, String label) {
        String raw = field.getText();
        if (raw == null || raw.isBlank()) return defaultVal;
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(label + " khong hop le: " + raw);
        }
    }

    private Set<String> parseCsvTableSet(String raw) {
        Set<String> set = new LinkedHashSet<>();
        if (raw == null || raw.isBlank()) return set;
        for (String part : raw.split(",")) {
            if (part == null) continue;
            String v = part.trim();
            if (!v.isEmpty()) set.add(v.toUpperCase(Locale.ROOT));
        }
        return set;
    }

    private void saveCredentialAction(boolean isSource) {
        try {
            DatabaseConfig config = buildDatabaseConfig(isSource);
            String schema = parseSchema(isSource ? sourceSchemaField : targetSchemaField, config);
            config.setSchemaName(schema);

            String defaultName = buildSuggestedCredentialName(isSource, config);
            String credentialName = JOptionPane.showInputDialog(
                    this,
                    "Nhap ten credential:",
                    defaultName
            );

            if (credentialName == null) {
                return;
            }

            String trimmed = credentialName.trim();
            if (trimmed.isEmpty()) {
                JOptionPane.showMessageDialog(this, "Ten credential khong duoc de trong.", "Loi", JOptionPane.ERROR_MESSAGE);
                return;
            }

            credentialStore.saveOrUpdate(trimmed, config);
            refreshSavedCredentialsCombos();
            appendLog("[OK] Da luu credential '" + trimmed + "' vao file JSON.");
        } catch (RuntimeException e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), "Loi luu credential", JOptionPane.ERROR_MESSAGE);
            appendLog("[LOI] Luu credential that bai: " + e.getMessage());
        }
    }

    private void loadCredentialAction(boolean isSource) {
        JComboBox<String> combo = isSource ? sourceCredentialCombo : targetCredentialCombo;
        if (combo == null) {
            return;
        }

        Object selected = combo.getSelectedItem();
        if (selected == null) {
            JOptionPane.showMessageDialog(this, "Hay chon credential de nap.", "Thong bao", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        String name = String.valueOf(selected);
        SavedCredentialJsonStore.SavedDbCredential credential = credentialByName.get(name);
        if (credential == null) {
            JOptionPane.showMessageDialog(this, "Khong tim thay credential da chon.", "Loi", JOptionPane.ERROR_MESSAGE);
            return;
        }

        applyCredentialToForm(isSource, credential.config());
        appendLog("[OK] Da nap credential '" + name + "' cho " + (isSource ? "SOURCE" : "TARGET") + ".");
    }

    private void refreshSavedCredentialsCombos() {
        try {
            java.util.List<SavedCredentialJsonStore.SavedDbCredential> allCredentials = credentialStore.loadAll();
            credentialByName.clear();
            for (SavedCredentialJsonStore.SavedDbCredential credential : allCredentials) {
                credentialByName.put(credential.name(), credential);
            }

            String[] names = credentialByName.keySet().toArray(String[]::new);
            if (sourceCredentialCombo != null) {
                sourceCredentialCombo.removeAllItems();
                for (String name : names) {
                    sourceCredentialCombo.addItem(name);
                }
            }
            if (targetCredentialCombo != null) {
                targetCredentialCombo.removeAllItems();
                for (String name : names) {
                    targetCredentialCombo.addItem(name);
                }
            }
        } catch (RuntimeException e) {
            appendLog("[WARN] Khong tai duoc danh sach credential: " + e.getMessage());
        }
    }

    private void applyCredentialToForm(boolean isSource, DatabaseConfig config) {
        if (config == null) {
            return;
        }

        JComboBox<String> typeCombo = isSource ? sourceDbTypeCombo : targetDbTypeCombo;
        JTextField hostField = isSource ? sourceHostField : targetHostField;
        JTextField portField = isSource ? sourcePortField : targetPortField;
        JTextField dbNameField = isSource ? sourceDbNameField : targetDbNameField;
        JTextField userField = isSource ? sourceUserField : targetUserField;
        JPasswordField passField = isSource ? sourcePassField : targetPassField;
        JTextField schemaField = isSource ? sourceSchemaField : targetSchemaField;

        if (typeCombo != null) {
            typeCombo.setSelectedItem(config.getType() == DatabaseType.ORACLE ? "Oracle" : "PostgreSQL");
        }
        if (hostField != null) {
            hostField.setText(config.getHost());
        }
        if (portField != null) {
            portField.setText(String.valueOf(config.getPort()));
        }
        if (dbNameField != null) {
            dbNameField.setText(config.getDatabaseName());
        }
        if (userField != null) {
            userField.setText(config.getUsername());
        }
        if (passField != null) {
            passField.setText(config.getPassword() == null ? "" : config.getPassword());
        }
        if (schemaField != null) {
            schemaField.setText(config.getSchemaName() == null ? "" : config.getSchemaName());
        }
    }

    private String buildSuggestedCredentialName(boolean isSource, DatabaseConfig config) {
        String role = isSource ? "source" : "target";
        String type = config.getType() == null ? "db" : config.getType().name().toLowerCase(Locale.ROOT);
        String host = config.getHost() == null || config.getHost().isBlank() ? "host" : config.getHost().trim();
        String db = config.getDatabaseName() == null || config.getDatabaseName().isBlank()
                ? "database"
                : config.getDatabaseName().trim();
        return role + "-" + type + "-" + host + "-" + db;
    }

    /** Test connection button handler. */
    private void testConnectionAction(boolean isSource) {
        ConnectionManager mgr = ConnectionManager.getInstance();
        String poolId = "UI_TEST_" + (isSource ? "SRC" : "TGT") + "_" + System.nanoTime();
        try {
            DatabaseConfig config = buildDatabaseConfig(isSource);
            String label = isSource ? "SOURCE" : "TARGET";
            appendLog("Dang test ket noi " + label + "...");
            mgr.createPool(poolId, config);
            if (mgr.testConnection(poolId)) {
                appendLog("[OK] " + label + " ket noi thanh cong.\n");
            } else {
                appendLog("[LOI] " + label + " khong the ket noi.\n");
            }
        } catch (Exception e) {
            appendLog("[LOI] " + e.getMessage() + "\n");
        } finally {
            mgr.closePool(poolId);
        }
    }

    /** Bottom panel: progress bar + start button + log area. */
    private JPanel createBottomPanel() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // Top row: Start button + Progress bar
        JPanel topRow = new JPanel(new BorderLayout(5, 5));
        btnStartMigration = new JButton("BAT DAU MIGRATION");
        btnStartMigration.setFont(new Font("Arial", Font.BOLD, 15));
        btnStartMigration.setBackground(new Color(30, 130, 80));
        btnStartMigration.setForeground(Color.WHITE);
        btnStartMigration.setPreferredSize(new Dimension(220, 48));
        btnStartMigration.addActionListener(e -> startMigrationAction());
        topRow.add(btnStartMigration, BorderLayout.WEST);

        progressBar = new JProgressBar(0, 100);
        progressBar.setStringPainted(true);
        progressBar.setFont(new Font("Arial", Font.PLAIN, 12));
        topRow.add(progressBar, BorderLayout.CENTER);

        panel.add(topRow, BorderLayout.NORTH);

        // Log area
        logArea = new JTextArea(10, 80);
        logArea.setEditable(false);
        logArea.setFont(new Font("Consolas", Font.PLAIN, 12));
        logArea.setBackground(new Color(18, 18, 30));
        logArea.setForeground(new Color(200, 230, 160));
        JScrollPane scrollPane = new JScrollPane(logArea);
        scrollPane.setBorder(BorderFactory.createTitledBorder("Execution Logs"));
        panel.add(scrollPane, BorderLayout.CENTER);

        return panel;
    }

    /**
     * Tạo một row gồm label + input component, stretch để fill chiều rộng.
     * Dùng GridLayout 2 cột (label 150px, field stretch).
     */
    private JPanel makeLabeledRow(String labelText, JComponent field) {
        JPanel row = new JPanel(new GridLayout(1, 2, 8, 0));
        JLabel label = new JLabel(labelText);
        label.setPreferredSize(new Dimension(150, 22));
        row.add(label);
        row.add(field);
        return row;
    }

    private JPanel makeLabeledRow(String labelText, JComboBox<String> combo) {
        return makeLabeledRow(labelText, (JComponent) combo);
    }

    // --- MAIN ---
    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (ClassNotFoundException | InstantiationException
                | IllegalAccessException | UnsupportedLookAndFeelException e) {
            System.err.println("Khong the dat LookAndFeel: " + e.getMessage());
        }
        SwingUtilities.invokeLater(() -> new MigrationAppUI().setVisible(true));
    }
}
