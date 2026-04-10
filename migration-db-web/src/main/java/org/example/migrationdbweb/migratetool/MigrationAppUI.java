package org.example.migrationdbweb.migratetool;




import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.swing.AbstractButton;
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
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.UnsupportedLookAndFeelException;
import javax.swing.border.TitledBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

public class MigrationAppUI extends JFrame {

    private static final Font UI_FONT = new Font("Segoe UI", Font.PLAIN, 13);
    private static final Font UI_TITLE_FONT = new Font("Segoe UI", Font.BOLD, 13);

    // --- UI Fields: Source DB ---
    private JComboBox<String> sourceDbTypeCombo;
    private JTextField sourceUrlField;
    private JTextField sourceHostField, sourcePortField, sourceDbNameField, sourceUserField;
    private JPasswordField sourcePassField;
    private JTextField sourceSchemaField;

    // --- UI Fields: Target DB ---
    private JComboBox<String> targetDbTypeCombo;
    private JTextField targetUrlField;
    private JTextField targetHostField, targetPortField, targetDbNameField, targetUserField;
    private JPasswordField targetPassField;
    private JTextField targetSchemaField;

    // --- UI Fields: Migration mode ---
    private JRadioButton optCopyAll, optStructureOnly, optDataOnly;
    private JCheckBox chkTruncateTarget, chkCopyNewOnly, chkCopyOnlyTargetEmptyTables;
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

    // --- Responsive layout containers ---
    private JPanel dbConfigPanel;
    private JPanel sourceDbSectionPanel;
    private JPanel targetDbSectionPanel;
    private JPanel optionsContentPanel;
    private JPanel sectionModePanel;
    private JPanel sectionCorePanel;
    private JPanel sectionAdvancedPanel;
    private JPanel sectionViewsPanel;
    private JPanel sectionRetryPanel;
    private JPanel sectionResumePanel;
    private boolean portraitLayoutActive;

    // --- Saved credentials (JSON local file) ---
    private JComboBox<String> sourceCredentialCombo;
    private JComboBox<String> targetCredentialCombo;
    private final SavedCredentialJsonStore credentialStore = new SavedCredentialJsonStore();
    private final Map<String, SavedCredentialJsonStore.SavedDbCredential> credentialByName = new LinkedHashMap<>();

    public MigrationAppUI() {
        setTitle("Database Migration Tool");
        setSize(1280, 860);
        setMinimumSize(new Dimension(820, 760));
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout(10, 10));
        initComponents();
    }

    private void initComponents() {
        JPanel mainContent = new JPanel();
        mainContent.setLayout(new javax.swing.BoxLayout(mainContent, javax.swing.BoxLayout.Y_AXIS));
        mainContent.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        dbConfigPanel = new JPanel();
        dbConfigPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        sourceDbSectionPanel = createDbConfigPanel("Source Database", true);
        targetDbSectionPanel = createDbConfigPanel("Target Database", false);
        mainContent.add(dbConfigPanel);
        mainContent.add(Box.createVerticalStrut(10));

        JPanel optionsPanel = createOptionsPanel();
        optionsPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        mainContent.add(optionsPanel);
        mainContent.add(Box.createVerticalGlue());

        JScrollPane topScrollPane = new JScrollPane(mainContent);
        topScrollPane.setBorder(BorderFactory.createEmptyBorder());
        topScrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        topScrollPane.getVerticalScrollBar().setUnitIncrement(16);

        JPanel bottomPanel = createBottomPanel();

        JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, topScrollPane, bottomPanel);
        splitPane.setResizeWeight(0.68);
        splitPane.setOneTouchExpandable(true);
        splitPane.setBorder(BorderFactory.createEmptyBorder());
        add(splitPane, BorderLayout.CENTER);

        bindOptionStateListeners();
        updateModeDependentOptionsState();

        refreshSavedCredentialsCombos();

        bindResponsiveContentListeners();
        applyResponsiveLayout(shouldUsePortraitLayout());
        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                applyResponsiveLayout(shouldUsePortraitLayout());
            }
        });
    }

    private void bindResponsiveContentListeners() {
        DocumentListener listener = new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                refreshResponsiveLayout();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                refreshResponsiveLayout();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                refreshResponsiveLayout();
            }
        };

        addResponsiveDocumentListener(sourceHostField, listener);
        addResponsiveDocumentListener(targetHostField, listener);
        addResponsiveDocumentListener(sourceUrlField, listener);
        addResponsiveDocumentListener(targetUrlField, listener);
    }

    private void addResponsiveDocumentListener(JTextField field, DocumentListener listener) {
        if (field != null && field.getDocument() != null) {
            field.getDocument().addDocumentListener(listener);
        }
    }

    private void refreshResponsiveLayout() {
        SwingUtilities.invokeLater(() -> applyResponsiveLayout(shouldUsePortraitLayout()));
    }

    private boolean shouldUsePortraitLayout() {
        return getHeight() > getWidth() || shouldStackByDbContentWidth();
    }

    private boolean shouldStackByDbContentWidth() {
        if (sourceDbSectionPanel == null || targetDbSectionPanel == null) {
            return false;
        }

        int requiredWidth = sourceDbSectionPanel.getPreferredSize().width
                + targetDbSectionPanel.getPreferredSize().width
                + 12;

        int availableWidth = dbConfigPanel != null && dbConfigPanel.getWidth() > 0
                ? dbConfigPanel.getWidth()
                : getContentPane().getWidth() - 20;

        return availableWidth > 0 && requiredWidth > availableWidth;
    }

    private void applyResponsiveLayout(boolean portrait) {
        if (portraitLayoutActive == portrait && dbConfigPanel.getComponentCount() > 0 && optionsContentPanel.getComponentCount() > 0) {
            return;
        }

        portraitLayoutActive = portrait;

        dbConfigPanel.removeAll();
        if (portrait) {
            dbConfigPanel.setLayout(new GridLayout(2, 1, 0, 10));
        } else {
            dbConfigPanel.setLayout(new GridLayout(1, 2, 12, 0));
        }
        dbConfigPanel.add(sourceDbSectionPanel);
        dbConfigPanel.add(targetDbSectionPanel);

        optionsContentPanel.removeAll();
        if (portrait) {
            optionsContentPanel.setLayout(new javax.swing.BoxLayout(optionsContentPanel, javax.swing.BoxLayout.Y_AXIS));
            addSectionsVertically(
                    optionsContentPanel,
                    sectionModePanel,
                    sectionCorePanel,
                    sectionAdvancedPanel,
                    sectionViewsPanel,
                    sectionRetryPanel,
                    sectionResumePanel
            );
        } else {
            optionsContentPanel.setLayout(new GridLayout(1, 2, 12, 0));
            optionsContentPanel.add(createOptionColumn(sectionModePanel, sectionAdvancedPanel, sectionRetryPanel));
            optionsContentPanel.add(createOptionColumn(sectionCorePanel, sectionViewsPanel, sectionResumePanel));
        }

        dbConfigPanel.revalidate();
        dbConfigPanel.repaint();
        optionsContentPanel.revalidate();
        optionsContentPanel.repaint();
    }

    private void addSectionsVertically(JPanel parent, JPanel... sections) {
        for (int i = 0; i < sections.length; i++) {
            JPanel section = sections[i];
            lockSectionHeight(section);
            section.setAlignmentX(Component.LEFT_ALIGNMENT);
            parent.add(section);
            if (i < sections.length - 1) {
                parent.add(Box.createVerticalStrut(10));
            }
        }
        parent.add(Box.createVerticalGlue());
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
        if (chkCopyOnlyTargetEmptyTables != null) {
            chkCopyOnlyTargetEmptyTables.setEnabled(!structureOnly);
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

    private void bindDefaultPortByDbType(JComboBox<String> dbTypeCombo, JTextField portField) {
        if (dbTypeCombo == null || portField == null) {
            return;
        }

        dbTypeCombo.addActionListener(e -> {
            Object selected = dbTypeCombo.getSelectedItem();
            if (selected == null) {
                return;
            }

            String dbType = String.valueOf(selected);
            if ("Oracle".equalsIgnoreCase(dbType)) {
                portField.setText("1521");
            } else if ("PostgreSQL".equalsIgnoreCase(dbType)) {
                portField.setText("5432");
            }
        });
    }

    /**
     * Tạo panel cấu hình kết nối DB (Source hoặc Target).
     * Thêm trường Schema override.
     */
    private JPanel createDbConfigPanel(String title, boolean isSource) {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setBorder(createDbSectionBorder(title));

        JComboBox<String> dbTypeCombo = new JComboBox<>(new String[]{"Oracle", "PostgreSQL"});
        dbTypeCombo.setSelectedItem(isSource ? "Oracle" : "PostgreSQL");
        JTextField urlField = new JTextField();
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
            sourceUrlField = urlField;
        } else {
            targetDbTypeCombo = dbTypeCombo; targetHostField = hostField;
            targetPortField = portField; targetDbNameField = dbNameField;
            targetUserField = userField; targetPassField = passField;
            targetSchemaField = schemaField;
            targetUrlField = urlField;
        }

        bindDefaultPortByDbType(dbTypeCombo, portField);

        JPanel formPanel = new JPanel();
        formPanel.setLayout(new javax.swing.BoxLayout(formPanel, javax.swing.BoxLayout.Y_AXIS));
        formPanel.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));

        // Row: Database Type
        formPanel.add(makeLabeledRow("Database Type:", dbTypeCombo));
        formPanel.add(Box.createVerticalStrut(6));

        // Row: JDBC URL
        urlField.setToolTipText("Preferred connection mode. If empty, Host + Port + DB Name / SID will be used.");
        formPanel.add(makeLabeledRow("JDBC URL (preferred):", urlField));
        formPanel.add(Box.createVerticalStrut(6));

        // Row: Host
        formPanel.add(makeLabeledRow("Host:", hostField));
        formPanel.add(Box.createVerticalStrut(6));

        // Row: Port
        formPanel.add(makeLabeledRow("Port:", portField));
        formPanel.add(Box.createVerticalStrut(6));

        // Row: DB Name / SID
        formPanel.add(makeLabeledRow("DB Name / SID:", dbNameField));
        formPanel.add(Box.createVerticalStrut(6));

        // Row: Username
        formPanel.add(makeLabeledRow("Username:", userField));
        formPanel.add(Box.createVerticalStrut(6));

        // Row: Password
        formPanel.add(makeLabeledRow("Password:", passField));
        formPanel.add(Box.createVerticalStrut(6));

        // Row: Schema — đặt preferred width để field rộng, dễ nhập
        schemaField.setPreferredSize(new Dimension(300, 26));
        schemaField.setToolTipText("Leave blank to use default schema (Oracle: USERNAME uppercase, PostgreSQL: public)");
        formPanel.add(makeLabeledRow("Schema (override):", schemaField));

        panel.add(formPanel, BorderLayout.CENTER);

        JButton btnTest = new JButton("Test Connection");
        btnTest.addActionListener(e -> testConnectionAction(isSource));
        JPanel btnPanel = new JPanel();
        btnPanel.setLayout(new javax.swing.BoxLayout(btnPanel, javax.swing.BoxLayout.X_AXIS));
        JComboBox<String> credentialCombo = new JComboBox<>();
        credentialCombo.setPrototypeDisplayValue("credential-name-xxxxxxxxxxxx");
        credentialCombo.setPreferredSize(new Dimension(180, 28));
        credentialCombo.setMaximumSize(new Dimension(220, 28));
        JButton btnLoadCredential = new JButton("Load Credential");
        JButton btnSaveCredential = new JButton("Save Credential");

        if (isSource) {
            sourceCredentialCombo = credentialCombo;
        } else {
            targetCredentialCombo = credentialCombo;
        }

        btnLoadCredential.addActionListener(e -> loadCredentialAction(isSource));
        btnSaveCredential.addActionListener(e -> saveCredentialAction(isSource));

        JLabel credentialLabel = new JLabel("Credential:");
        credentialLabel.setFont(UI_FONT);
        btnPanel.add(credentialLabel);
        btnPanel.add(Box.createHorizontalStrut(6));
        btnPanel.add(credentialCombo);
        btnPanel.add(Box.createHorizontalStrut(8));
        btnPanel.add(btnLoadCredential);
        btnPanel.add(Box.createHorizontalStrut(6));
        btnPanel.add(btnSaveCredential);
        btnPanel.add(Box.createHorizontalGlue());
        btnPanel.add(btnTest);
        panel.add(btnPanel, BorderLayout.SOUTH);

        return panel;
    }

    /**
     * Tạo panel tùy chọn migration — chia thành các section rõ ràng.
     */
    private JPanel createOptionsPanel() {
        JPanel wrapper = new JPanel(new BorderLayout(10, 8));
        wrapper.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));

        sectionModePanel = createSection1ModePanel();
        sectionCorePanel = createSection2CoreOptionsPanel();
        sectionAdvancedPanel = createSection3AdvancedPanel();
        sectionViewsPanel = createSection4ViewsPanel();
        sectionRetryPanel = createSection5RetryPanel();
        sectionResumePanel = createSection6ResumePanel();

        optionsContentPanel = new JPanel();
        optionsContentPanel.setOpaque(false);
        wrapper.add(optionsContentPanel, BorderLayout.CENTER);

        JPanel notePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 2));
        JLabel note = new JLabel("If no new logs appear for over 5 minutes, please verify database connectivity.");
        note.setFont(new Font("Segoe UI", Font.ITALIC, 12));
        note.setForeground(new Color(120, 100, 80));
        notePanel.add(note);
        wrapper.add(notePanel, BorderLayout.SOUTH);

        return wrapper;
    }

    private JPanel createOptionColumn(JPanel... sections) {
        JPanel column = new JPanel();
        column.setLayout(new javax.swing.BoxLayout(column, javax.swing.BoxLayout.Y_AXIS));

        for (int i = 0; i < sections.length; i++) {
            JPanel section = sections[i];
            lockSectionHeight(section);
            section.setAlignmentX(Component.LEFT_ALIGNMENT);
            column.add(section);
            if (i < sections.length - 1) {
                column.add(Box.createVerticalStrut(10));
            }
        }

        column.add(Box.createVerticalGlue());
        return column;
    }

    private JPanel createSection1ModePanel() {
        JPanel section = createOptionSection("1. MIGRATION MODE");
        JPanel content = createOptionContentPanel();

        optCopyAll = new JRadioButton("Copy structure and data", true);
        optStructureOnly = new JRadioButton("Structure only (DDL)");
        optDataOnly = new JRadioButton("Data only (DML)");
        styleOptionToggle(optCopyAll);
        styleOptionToggle(optStructureOnly);
        styleOptionToggle(optDataOnly);

        ButtonGroup modeGroup = new ButtonGroup();
        modeGroup.add(optCopyAll);
        modeGroup.add(optStructureOnly);
        modeGroup.add(optDataOnly);

        content.add(createSingleToggleRow(optCopyAll, 270));
        content.add(Box.createVerticalStrut(8));
        content.add(createSingleToggleRow(optStructureOnly, 270));
        content.add(Box.createVerticalStrut(8));
        content.add(createSingleToggleRow(optDataOnly, 270));
        section.add(content, BorderLayout.NORTH);
        return section;
    }

    private JPanel createSection2CoreOptionsPanel() {
        JPanel section = createOptionSection("2. CORE OPTIONS");
        JPanel content = createOptionContentPanel();

        chkTruncateTarget = new JCheckBox("Clear existing target data before migration (TRUNCATE)");
        chkCopyNewOnly = new JCheckBox("Copy only new records (by PK, skip duplicates)");
        chkCopyOnlyTargetEmptyTables = new JCheckBox("Copy data only for empty target tables");
        styleOptionToggle(chkTruncateTarget);
        styleOptionToggle(chkCopyNewOnly);
        styleOptionToggle(chkCopyOnlyTargetEmptyTables);
        content.add(chkTruncateTarget);
        content.add(Box.createVerticalStrut(4));
        content.add(chkCopyNewOnly);
        content.add(Box.createVerticalStrut(4));
        content.add(chkCopyOnlyTargetEmptyTables);
        content.add(Box.createVerticalStrut(8));

        JPanel row2 = createInlineRowPanel();
        row2.add(makeOptionLabel("Batch size (rows/batch):"));
        batchSizeField = new JTextField("1000", 7);
        batchSizeField.setToolTipText("Rows processed per batch. Increase for speed, decrease for lower memory usage.");
        styleOptionField(batchSizeField);
        row2.add(batchSizeField);
        row2.add(Box.createHorizontalStrut(14));
        row2.add(makeOptionLabel("Limit/table (0 = all):"));
        limitDataField = new JTextField("0", 7);
        limitDataField.setToolTipText("Use 0 to copy all rows.");
        styleOptionField(limitDataField);
        row2.add(limitDataField);
        row2.add(Box.createHorizontalStrut(14));
        row2.add(makeOptionLabel("Data threads (0 = auto):"));
        dataThreadsField = new JTextField("0", 5);
        dataThreadsField.setToolTipText("Thread count for data phase. 0 = auto by pool/table.");
        styleOptionField(dataThreadsField);
        row2.add(dataThreadsField);
        content.add(row2);
        content.add(Box.createVerticalStrut(6));

        includeTablesField = new JTextField("", 28);
        includeTablesField.setToolTipText("Supports wildcard * (e.g., user*,order_*). Empty = migrate all tables.");
        styleOptionField(includeTablesField);
        includeTablesField.setPreferredSize(new Dimension(260, 26));
        content.add(createLabeledStretchFieldRow("Include tables (CSV):", includeTablesField, 140));
        content.add(Box.createVerticalStrut(6));

        excludeTablesField = new JTextField("", 28);
        excludeTablesField.setToolTipText("Supports wildcard * (e.g., temp_*,audit*).");
        styleOptionField(excludeTablesField);
        excludeTablesField.setPreferredSize(new Dimension(260, 26));
        content.add(createLabeledStretchFieldRow("Exclude tables (CSV):", excludeTablesField, 140));

        section.add(content, BorderLayout.NORTH);
        return section;
    }

    private JPanel createSection3AdvancedPanel() {
        JPanel section = createOptionSection("3. ADVANCED OBJECTS");
        JPanel content = createOptionContentPanel();

        chkMigrateSequences = new JCheckBox("Sequences");
        chkMigrateIndexes   = new JCheckBox("Indexes");
        chkMigrateFunctions = new JCheckBox("Functions/Procedures");
        chkMigrateTriggers = new JCheckBox("Triggers");
        styleOptionToggle(chkMigrateSequences);
        styleOptionToggle(chkMigrateIndexes);
        styleOptionToggle(chkMigrateFunctions);
        styleOptionToggle(chkMigrateTriggers);

        content.add(createTogglePairRow(chkMigrateSequences, chkMigrateIndexes, 190, 150));

        content.add(Box.createVerticalStrut(8));

        content.add(createTogglePairRow(chkMigrateFunctions, chkMigrateTriggers, 190, 150));

        // Add spacing so section 3 height is closer to section 4 in two-column layout.
        content.add(Box.createVerticalStrut(34));

        section.add(content, BorderLayout.NORTH);
        return section;
    }

    private JPanel createSection4ViewsPanel() {
        JPanel section = createOptionSection("4. VIEWS");
        JPanel content = createOptionContentPanel();

        chkMigrateViews = new JCheckBox("Migrate views");
        chkReplaceExistingViews = new JCheckBox("Replace existing views (DROP + CREATE)");
        styleOptionToggle(chkMigrateViews);
        styleOptionToggle(chkReplaceExistingViews);
        content.add(chkMigrateViews);
        content.add(Box.createVerticalStrut(4));
        content.add(chkReplaceExistingViews);
        content.add(Box.createVerticalStrut(6));

        JPanel includeViewsPanel = createInlineRowPanel();
        includeViewsPanel.add(makeOptionLabel("Include views (CSV):"));
        includeViewsField = new JTextField("", 28);
        includeViewsField.setToolTipText("Supports wildcard * (e.g., v_user*,v_order_*).");
        styleOptionField(includeViewsField);
        includeViewsField.setPreferredSize(new Dimension(230, 26));
        includeViewsPanel.add(includeViewsField);
        content.add(includeViewsPanel);
        content.add(Box.createVerticalStrut(6));

        JPanel excludeViewsPanel = createInlineRowPanel();
        excludeViewsPanel.add(makeOptionLabel("Exclude views  (CSV):"));
        excludeViewsField = new JTextField("", 28);
        excludeViewsField.setToolTipText("Supports wildcard * (e.g., v_temp*).");
        styleOptionField(excludeViewsField);
        excludeViewsField.setPreferredSize(new Dimension(230, 26));
        excludeViewsPanel.add(excludeViewsField);
        content.add(excludeViewsPanel);

        section.add(content, BorderLayout.NORTH);
        return section;
    }

    private JPanel createSection5RetryPanel() {
        JPanel section = createOptionSection("5. RETRY");
        JPanel content = createOptionContentPanel();

        chkRetryEnabled = new JCheckBox("Enable retry for temporary failures");
        styleOptionToggle(chkRetryEnabled);
        content.add(chkRetryEnabled);
        content.add(Box.createVerticalStrut(8));

        JPanel retryRow1 = createInlineRowPanel();
        retryRow1.add(makeOptionLabel("Max retry attempts:"));
        retryMaxAttemptsField = new JTextField("3", 5);
        styleOptionField(retryMaxAttemptsField);
        retryRow1.add(retryMaxAttemptsField);
        retryRow1.add(Box.createHorizontalStrut(10));
        retryRow1.add(makeOptionLabel("Initial delay (ms):"));
        retryDelayMsField = new JTextField("2000", 7);
        styleOptionField(retryDelayMsField);
        retryRow1.add(retryDelayMsField);
        retryRow1.add(Box.createHorizontalStrut(10));
        retryRow1.add(makeOptionLabel("Backoff multiplier:"));
        retryBackoffField = new JTextField("2.0", 5);
        styleOptionField(retryBackoffField);
        retryRow1.add(retryBackoffField);
        content.add(retryRow1);

        // Add spacing so section 5 height is closer to section 6 in two-column layout.
        content.add(Box.createVerticalStrut(28));

        section.add(content, BorderLayout.NORTH);
        return section;
    }

    private JPanel createSection6ResumePanel() {
        JPanel section = createOptionSection("6. RESUME");
        JPanel content = createOptionContentPanel();

        chkResumeEnabled = new JCheckBox("Enable resume (continue from checkpoint)");
        styleOptionToggle(chkResumeEnabled);
        content.add(chkResumeEnabled);
        content.add(Box.createVerticalStrut(8));

        JPanel resumeRow1 = createInlineRowPanel();
        resumeRow1.add(makeOptionLabel("File checkpoint:"));
        resumeStateFileField = new JTextField(".migration-resume.properties", 26);
        resumeStateFileField.setToolTipText("Absolute or relative path to checkpoint file.");
        styleOptionField(resumeStateFileField);
        resumeStateFileField.setPreferredSize(new Dimension(220, 26));
        resumeRow1.add(resumeStateFileField);
        this.chkResumeReset = new JCheckBox("Reset existing checkpoint?");
        styleOptionToggle(this.chkResumeReset);
        resumeRow1.add(Box.createHorizontalStrut(10));
        resumeRow1.add(this.chkResumeReset);
        content.add(resumeRow1);

        section.add(content, BorderLayout.NORTH);
        return section;
    }

    private JPanel createOptionContentPanel() {
        JPanel content = new JPanel();
        content.setLayout(new javax.swing.BoxLayout(content, javax.swing.BoxLayout.Y_AXIS));
        content.setOpaque(false);
        return content;
    }

    private JPanel createInlineRowPanel() {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        return row;
    }

    private JPanel createSingleToggleRow(AbstractButton toggle, int width) {
        JPanel row = new JPanel();
        row.setLayout(new javax.swing.BoxLayout(row, javax.swing.BoxLayout.X_AXIS));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.add(createToggleCell(toggle, width));
        row.add(Box.createHorizontalGlue());
        return row;
    }

    private JPanel createTogglePairRow(AbstractButton left, AbstractButton right, int leftWidth, int rightWidth) {
        JPanel row = new JPanel();
        row.setLayout(new javax.swing.BoxLayout(row, javax.swing.BoxLayout.X_AXIS));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.add(createToggleCell(left, leftWidth));
        row.add(Box.createHorizontalStrut(16));
        row.add(createToggleCell(right, rightWidth));
        row.add(Box.createHorizontalGlue());
        return row;
    }

    private JPanel createToggleCell(AbstractButton toggle, int width) {
        JPanel cell = new JPanel(new BorderLayout());
        cell.setOpaque(false);
        cell.setPreferredSize(new Dimension(width, 26));
        cell.setMinimumSize(new Dimension(width, 26));
        cell.setMaximumSize(new Dimension(width, 26));
        cell.add(toggle, BorderLayout.WEST);
        return cell;
    }

    private JPanel createLabeledStretchFieldRow(String labelText, JTextField field, int labelWidth) {
        JPanel row = new JPanel(new BorderLayout(8, 0));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel label = makeOptionLabel(labelText);
        label.setPreferredSize(new Dimension(labelWidth, 26));
        row.add(label, BorderLayout.WEST);
        row.add(field, BorderLayout.CENTER);
        return row;
    }

    private JLabel makeOptionLabel(String text) {
        JLabel label = new JLabel(text);
        label.setFont(UI_FONT);
        return label;
    }

    private void styleOptionToggle(AbstractButton toggle) {
        toggle.setFont(UI_FONT);
        toggle.setFocusPainted(false);
        toggle.setMargin(new Insets(2, 2, 2, 2));
        toggle.setOpaque(false);
        toggle.setAlignmentX(Component.LEFT_ALIGNMENT);
    }

    private void styleOptionField(JTextField field) {
        field.setFont(UI_FONT);
        Dimension preferred = field.getPreferredSize();
        field.setPreferredSize(new Dimension(preferred.width, 26));
    }

    private void lockSectionHeight(JPanel section) {
        Dimension preferred = section.getPreferredSize();
        section.setMaximumSize(new Dimension(Integer.MAX_VALUE, preferred.height + 2));
    }

    private JPanel createOptionSection(String title) {
        JPanel section = new JPanel(new BorderLayout(5, 5));
        section.setBorder(createSectionBorder(title));
        return section;
    }

    private javax.swing.border.Border createSectionBorder(String title) {
        TitledBorder titledBorder = BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(new Color(180, 180, 180)),
                title,
                TitledBorder.LEFT,
                TitledBorder.TOP,
                UI_TITLE_FONT
        );
        return BorderFactory.createCompoundBorder(titledBorder, BorderFactory.createEmptyBorder(6, 6, 6, 6));
    }

    private javax.swing.border.Border createDbSectionBorder(String title) {
        TitledBorder titledBorder = BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(new Color(78, 93, 108), 2),
                title,
                TitledBorder.LEFT,
                TitledBorder.TOP,
                UI_TITLE_FONT
        );
        titledBorder.setTitleColor(new Color(36, 49, 61));
        return BorderFactory.createCompoundBorder(titledBorder, BorderFactory.createEmptyBorder(6, 6, 6, 6));
    }

    /** SwingUtilities.invokeLater wrapper */
    public void appendLog(String message) {
        SwingUtilities.invokeLater(() -> {
            logArea.append((message == null ? "" : message) + "\n");
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
            JOptionPane.showMessageDialog(this, e.getMessage(), "Configuration Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        // ── 2. Parse simple options ───────────────────────────────────
        boolean isStructureOnly = optStructureOnly.isSelected();
        boolean isDataOnly = optDataOnly.isSelected();
        boolean truncateTarget = chkTruncateTarget.isSelected();
        boolean copyNewOnly = chkCopyNewOnly.isSelected();
        boolean copyOnlyTargetEmptyTables = chkCopyOnlyTargetEmptyTables.isSelected();

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

        appendLog("=== START MIGRATION ===");
        appendLog("Source: " + sourceConfig);
        appendLog("Target: " + targetConfig);
        appendLog("Schema source=" + sourceSchema + " | target=" + targetSchema);
        appendLog("Mode: " + (isStructureOnly ? "STRUCTURE_ONLY" : isDataOnly ? "DATA_ONLY" : "ALL"));
        appendLog("Batch size: " + batchSize + " | Limit: " + (limitRows == null ? "ALL" : limitRows));
        appendLog("Data threads: " + (dataThreads == 0 ? "AUTO" : dataThreads));
        appendLog("Truncate: " + truncateTarget
            + " | CopyNewOnly: " + copyNewOnly
            + " | CopyOnlyTargetEmptyTables: " + copyOnlyTargetEmptyTables);
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
                copyOnlyTargetEmptyTables,
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
        JTextField jdbcUrlField = isSource ? sourceUrlField : targetUrlField;
        String jdbcUrl = jdbcUrlField == null ? "" : jdbcUrlField.getText();

        String host;
        int port;
        String dbName;
        if (jdbcUrl != null && !jdbcUrl.trim().isEmpty()) {
            host = optionalText(isSource ? sourceHostField : targetHostField);
            port = parsePortOrDefault(isSource, defaultPortFor(dbType));
            dbName = optionalText(isSource ? sourceDbNameField : targetDbNameField);
        } else {
            host = requireText(isSource ? sourceHostField : targetHostField, "Host");
            port = parsePort(isSource);
            dbName = requireText(isSource ? sourceDbNameField : targetDbNameField, "DB Name / SID");
        }

        String username = requireText(isSource ? sourceUserField : targetUserField, "Username");
        String password = new String(isSource ? sourcePassField.getPassword() : targetPassField.getPassword());
        DatabaseConfig config = new DatabaseConfig(dbType, host, port, dbName, username, password);
        config.setJdbcUrl(jdbcUrl == null ? null : jdbcUrl.trim());
        JTextField schemaField = isSource ? sourceSchemaField : targetSchemaField;
        if (schemaField != null) {
            String rawSchema = schemaField.getText();
            if (rawSchema != null && !rawSchema.trim().isEmpty()) {
                config.setSchemaName(rawSchema.trim());
            }
        }
        return config;
    }

    /** Schema: uu tien schema param trong JDBC URL, sau do moi den field override/default. */
    private String parseSchema(JTextField schemaField, DatabaseConfig config) {
        String jdbcUrl = config == null ? null : config.getJdbcUrlValue();
        java.util.Optional<String> schemaFromUrl = config != null && config.getType() == DatabaseType.ORACLE
                ? JdbcUrlParamResolver.resolveOracleSchemaFromUrl(jdbcUrl)
                : JdbcUrlParamResolver.resolvePostgresSchemaFromUrl(jdbcUrl);
        if (schemaFromUrl.isPresent()) {
            return schemaFromUrl.get();
        }

        String override = schemaField.getText();
        if (override != null && !override.trim().isBlank()) {
            return override.trim();
        }
        if (config == null) {
            return "public";
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
        throw new IllegalArgumentException("Invalid database type: " + selected);
    }

    private String requireText(JTextField field, String label) {
        String v = field.getText();
        if (v == null || v.isBlank()) throw new IllegalArgumentException(label + " must not be empty.");
        return v.trim();
    }

    private String optionalText(JTextField field) {
        if (field == null || field.getText() == null) {
            return "";
        }
        return field.getText().trim();
    }

    private int parsePort(boolean isSource) {
        String raw = (isSource ? sourcePortField : targetPortField).getText();
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid port: " + raw);
        }
    }

    private int parsePortOrDefault(boolean isSource, int defaultPort) {
        String raw = (isSource ? sourcePortField : targetPortField).getText();
        if (raw == null || raw.isBlank()) {
            return defaultPort;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return defaultPort;
        }
    }

    private int defaultPortFor(DatabaseType type) {
        return type == DatabaseType.ORACLE ? 1521 : 5432;
    }

    private Integer parseIntField(JTextField field, int defaultVal, String label) {
        String raw = field.getText();
        if (raw == null || raw.isBlank()) return defaultVal;
        try {
            int v = Integer.parseInt(raw.trim());
            if (v < 0) throw new IllegalArgumentException(label + " must be >= 0.");
            return v;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid " + label + ": " + raw);
        }
    }

    private long parseLongField(JTextField field, long defaultVal, String label) {
        String raw = field.getText();
        if (raw == null || raw.isBlank()) return defaultVal;
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid " + label + ": " + raw);
        }
    }

    private double parseDoubleField(JTextField field, double defaultVal, String label) {
        String raw = field.getText();
        if (raw == null || raw.isBlank()) return defaultVal;
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid " + label + ": " + raw);
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
                    "Enter credential name:",
                    defaultName
            );

            if (credentialName == null) {
                return;
            }

            String trimmed = credentialName.trim();
            if (trimmed.isEmpty()) {
                JOptionPane.showMessageDialog(this, "Credential name must not be empty.", "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }

            credentialStore.saveOrUpdate(trimmed, config);
            refreshSavedCredentialsCombos();
            appendLog("[OK] Saved credential '" + trimmed + "' to JSON file.");
        } catch (RuntimeException e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), "Credential Save Error", JOptionPane.ERROR_MESSAGE);
            appendLog("[ERROR] Failed to save credential: " + e.getMessage());
        }
    }

    private void loadCredentialAction(boolean isSource) {
        JComboBox<String> combo = isSource ? sourceCredentialCombo : targetCredentialCombo;
        if (combo == null) {
            return;
        }

        Object selected = combo.getSelectedItem();
        if (selected == null) {
            JOptionPane.showMessageDialog(this, "Please choose a credential to load.", "Information", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        String name = String.valueOf(selected);
        SavedCredentialJsonStore.SavedDbCredential credential = credentialByName.get(name);
        if (credential == null) {
            JOptionPane.showMessageDialog(this, "Selected credential was not found.", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        applyCredentialToForm(isSource, credential.config());
        appendLog("[OK] Loaded credential '" + name + "' for " + (isSource ? "SOURCE" : "TARGET") + ".");
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
            appendLog("[WARN] Unable to load credential list: " + e.getMessage());
        }
    }

    private void applyCredentialToForm(boolean isSource, DatabaseConfig config) {
        if (config == null) {
            return;
        }

        JComboBox<String> typeCombo = isSource ? sourceDbTypeCombo : targetDbTypeCombo;
        JTextField urlField = isSource ? sourceUrlField : targetUrlField;
        JTextField hostField = isSource ? sourceHostField : targetHostField;
        JTextField portField = isSource ? sourcePortField : targetPortField;
        JTextField dbNameField = isSource ? sourceDbNameField : targetDbNameField;
        JTextField userField = isSource ? sourceUserField : targetUserField;
        JPasswordField passField = isSource ? sourcePassField : targetPassField;
        JTextField schemaField = isSource ? sourceSchemaField : targetSchemaField;

        if (typeCombo != null) {
            typeCombo.setSelectedItem(config.getType() == DatabaseType.ORACLE ? "Oracle" : "PostgreSQL");
        }
        if (urlField != null) {
            urlField.setText(config.getJdbcUrlValue() == null ? "" : config.getJdbcUrlValue());
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
        if ((host == null || host.isBlank() || "host".equals(host))
                && config.getJdbcUrlValue() != null
                && !config.getJdbcUrlValue().isBlank()) {
            host = "jdbc-url";
        }
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
            appendLog("Testing connection " + label + "...");
            mgr.createPool(poolId, config);
            if (mgr.testConnection(poolId)) {
                appendLog("[OK] " + label + " connection successful.\n");
            } else {
                appendLog("[ERROR] " + label + " cannot connect.\n");
            }
        } catch (Exception e) {
            appendLog("[ERROR] " + e.getMessage() + "\n");
        } finally {
            mgr.closePool(poolId);
        }
    }

    /** Bottom panel: progress bar + start button + log area. */
    private JPanel createBottomPanel() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setBorder(BorderFactory.createEmptyBorder(8, 10, 10, 10));

        // Top row: Start button + Progress bar
        JPanel topRow = new JPanel(new BorderLayout(5, 5));
        btnStartMigration = createPrimaryActionButton("START MIGRATION");
        btnStartMigration.setForeground(Color.WHITE);
        btnStartMigration.addActionListener(e -> startMigrationAction());
        topRow.add(btnStartMigration, BorderLayout.WEST);

        progressBar = new JProgressBar(0, 100);
        progressBar.setStringPainted(true);
        progressBar.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        progressBar.setPreferredSize(new Dimension(200, 32));
        topRow.add(progressBar, BorderLayout.CENTER);

        panel.add(topRow, BorderLayout.NORTH);

        // Log area
        logArea = new JTextArea(12, 80);
        logArea.setEditable(false);
        logArea.setFont(new Font("Consolas", Font.PLAIN, 12));
        logArea.setBackground(new Color(18, 18, 30));
        logArea.setForeground(new Color(200, 230, 160));
        JScrollPane scrollPane = new JScrollPane(logArea);
        scrollPane.setBorder(createSectionBorder("Execution Logs"));
        panel.add(scrollPane, BorderLayout.CENTER);

        return panel;
    }

    private JButton createPrimaryActionButton(String text) {
        JButton button = new JButton(text) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                Color top;
                Color bottom;
                Color border;
                if (!isEnabled()) {
                    top = new Color(120, 120, 120);
                    bottom = new Color(95, 95, 95);
                    border = new Color(80, 80, 80);
                } else if (getModel().isPressed()) {
                    top = new Color(191, 59, 39);
                    bottom = new Color(150, 39, 25);
                    border = new Color(111, 24, 14);
                } else if (getModel().isRollover()) {
                    top = new Color(255, 122, 55);
                    bottom = new Color(243, 93, 34);
                    border = new Color(191, 67, 14);
                } else {
                    top = new Color(246, 103, 43);
                    bottom = new Color(223, 72, 19);
                    border = new Color(171, 52, 11);
                }

                g2.setColor(new Color(0, 0, 0, 35));
                g2.fillRoundRect(2, 3, getWidth() - 4, getHeight() - 4, 12, 12);

                g2.setPaint(new GradientPaint(0, 0, top, 0, getHeight(), bottom));
                g2.fillRoundRect(0, 0, getWidth() - 3, getHeight() - 3, 12, 12);

                g2.setColor(border);
                g2.drawRoundRect(0, 0, getWidth() - 3, getHeight() - 3, 12, 12);
                g2.dispose();

                super.paintComponent(g);
            }
        };

        button.setFont(new Font("Segoe UI", Font.BOLD, 15));
        button.setHorizontalAlignment(JButton.CENTER);
        button.setCursor(new Cursor(Cursor.HAND_CURSOR));
        button.setFocusPainted(false);
        button.setRolloverEnabled(true);
        button.setBorderPainted(false);
        button.setContentAreaFilled(false);
        button.setOpaque(false);
        button.setMargin(new Insets(8, 18, 8, 18));
        button.setPreferredSize(new Dimension(240, 50));
        return button;
    }

    /**
     * Tạo một row gồm label + input component, stretch để fill chiều rộng.
     * Dùng GridLayout 2 cột (label 150px, field stretch).
     */
    private JPanel makeLabeledRow(String labelText, JComponent field) {
        JPanel row = new JPanel(new BorderLayout(8, 0));
        JLabel label = new JLabel(labelText);
        label.setFont(UI_FONT);
        label.setPreferredSize(new Dimension(150, 26));
        field.setFont(UI_FONT);
        row.add(label, BorderLayout.WEST);
        row.add(field, BorderLayout.CENTER);
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
            System.err.println("Unable to set LookAndFeel: " + e.getMessage());
        }
        SwingUtilities.invokeLater(() -> new MigrationAppUI().setVisible(true));
    }
}
