package com.dabomstew.pkrandom.pokemon.editors;

import com.dabomstew.pkrandom.log.ManualEditRegistry;
import com.dabomstew.pkromio.gamedata.*;
import com.dabomstew.pkromio.romhandlers.RomHandler;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Evolutions Sheet editor with proper editing capabilities
 * Implements dynamic cell editors based on evolution method
 */
public class EvolutionsSheetPanel extends JPanel {

    private final RomHandler romHandler;
    private final List<Species> pokemonList;
    private final List<Move> moveList;
    private final List<Item> itemList;
    private final int maxEvolutions = 6;

    private JTable frozenTable;
    private JTable mainTable;
    private EvolutionsTableModel tableModel;
    private boolean copyPasteModeEnabled = false;
    private final EditorUtils.FindState findState = new EditorUtils.FindState();
    private final PokemonIconCache iconCache;
    private final Map<Integer, Species> speciesByNumber;
    private final Map<Species, List<Evolution>> evolutionsBackup = new HashMap<>();

    public EvolutionsSheetPanel(RomHandler romHandler) {
        this.romHandler = romHandler;
        this.pokemonList = romHandler.getSpecies();
        this.moveList = romHandler.getMoves();
        this.itemList = romHandler.getItems();
        this.iconCache = PokemonIconCache.get(romHandler);
        this.speciesByNumber = new HashMap<>();
        for (Species species : pokemonList) {
            if (species != null) {
                speciesByNumber.put(species.getNumber(), species);
            }
        }
        initializeUI();
        createBackup();
    }

    private void initializeUI() {
        setLayout(new BorderLayout());
        setBackground(EditorTheme.surface());

        add(createStyledToolbar(), BorderLayout.NORTH);
        JPanel tablePanel = createFrozenColumnTable();
        add(tablePanel, BorderLayout.CENTER);
    }

    private JPanel createStyledToolbar() {
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 8));
        toolbar.setBackground(EditorTheme.toolbar());
        toolbar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, EditorTheme.border()),
                new EmptyBorder(5, 5, 5, 5)));

        JButton saveButton = EditorUtils.createStyledButton("Save", new Color(76, 175, 80));
        saveButton.addActionListener(e -> save());

        JButton reloadButton = EditorUtils.createStyledButton("Reload", new Color(96, 96, 96));
        reloadButton.addActionListener(e -> reload());

        JButton exportButton = EditorUtils.createStyledButton("Export CSV", new Color(33, 150, 243));
        exportButton.addActionListener(e -> exportToCSV());

        JButton importButton = EditorUtils.createStyledButton("Import CSV", new Color(0, 188, 212));
        importButton.addActionListener(e -> importFromCSV());

        JToggleButton copyPasteButton = EditorUtils.createStyledToggleButton("Copy/Paste Mode", new Color(255, 152, 0));
        copyPasteButton.addActionListener(e -> toggleCopyPasteMode(copyPasteButton.isSelected()));

        JButton findButton = EditorUtils.createStyledButton("Find", new Color(0, 150, 136));
        findButton.addActionListener(e -> showFindDialog());

        toolbar.add(saveButton);
        toolbar.add(reloadButton);
        toolbar.add(exportButton);
        toolbar.add(importButton);
        toolbar.add(Box.createHorizontalStrut(5));
        toolbar.add(copyPasteButton);
        toolbar.add(findButton);
        toolbar.add(Box.createHorizontalStrut(10));

        JLabel infoLabel = new JLabel("Evolution Methods - Edit how Pokemon evolve");
        infoLabel.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        infoLabel.setForeground(EditorTheme.mutedText());
        toolbar.add(infoLabel);

        return toolbar;
    }

    private JPanel createFrozenColumnTable() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(EditorTheme.surface());

        tableModel = new EvolutionsTableModel(pokemonList, moveList, itemList, maxEvolutions, romHandler);
        // Keep Species.evolutionsTo in sync after every edit and on restore (fireTableDataChanged).
        tableModel.addTableModelListener(e -> {
            // On a full-table refresh (CSV import / restore via fireTableDataChanged),
            // strip phantom EvolutionType.NONE entries before rebuilding the reverse
            // links so a no-op round-trip never leaves NONE slots behind. Single-cell
            // edits are NOT stripped here: editing the "Evolves To" column deliberately
            // creates a NONE-typed slot that the user fills in with a Method next.
            if (e == null || e.getFirstRow() == javax.swing.event.TableModelEvent.HEADER_ROW
                    || e.getType() == javax.swing.event.TableModelEvent.UPDATE
                            && e.getFirstRow() == 0
                            && e.getLastRow() == Integer.MAX_VALUE) {
                EditorUtils.stripNoneEvolutions(romHandler.getSpeciesInclFormes());
            }
            EditorUtils.rebuildEvolutionReverseLinks(romHandler.getSpeciesInclFormes());
        });

        // Frozen table
        TableModel frozenModel = new AbstractTableModel() {
            public int getRowCount() {
                return tableModel.getRowCount();
            }

            public int getColumnCount() {
                return 2;
            }

            public Object getValueAt(int row, int col) {
                return tableModel.getValueAt(row, col);
            }

            public String getColumnName(int col) {
                return tableModel.getColumnName(col);
            }

            public Class<?> getColumnClass(int col) {
                return tableModel.getColumnClass(col);
            }
        };

        frozenTable = new JTable(frozenModel) {
            @Override
            public void scrollRectToVisible(java.awt.Rectangle aRect) {
                // Disable horizontal scrolling for frozen table
                aRect.x = 0;
                super.scrollRectToVisible(aRect);
            }

            @Override
            public void changeSelection(int rowIndex, int columnIndex, boolean toggle, boolean extend) {
                super.changeSelection(rowIndex, columnIndex, toggle, extend);
                if (rowIndex >= 0 && EditorUtils.isFullRowSelectionActive(this) && getColumnCount() > 0) {
                    getColumnModel().getSelectionModel().setSelectionInterval(0, getColumnCount() - 1);
                    EditorUtils.runWithFrozenSyncSuppressed(mainTable, () -> {
                        if (mainTable.getColumnCount() > 0) {
                            mainTable.setColumnSelectionInterval(0, mainTable.getColumnCount() - 1);
                        } else {
                            mainTable.getColumnModel().getSelectionModel().clearSelection();
                        }
                    });
                }
            }
        };
        styleTable(frozenTable, true);
        TableLayoutDefaults.configureFrozenColumns(frozenTable.getColumnModel(), iconCache.hasIcons());
        TableLayoutDefaults.applyRowHeight(frozenTable, iconCache.hasIcons());

        if (iconCache.hasIcons()) {
            frozenTable.getColumnModel().getColumn(1).setCellRenderer(new BaseSpeciesRenderer());
        }

        // Add mouse listener for ID column row selection behavior
        java.awt.event.MouseAdapter frozenRowSelector = new java.awt.event.MouseAdapter() {
            private int lastClickedRow = -1;
            private boolean draggingFullRow = false;

            @Override
            public void mousePressed(java.awt.event.MouseEvent e) {
                int row = frozenTable.rowAtPoint(e.getPoint());
                int col = frozenTable.columnAtPoint(e.getPoint());

                // Only intercept clicks on ID column (column 0)
                if (col == 0 && row >= 0) {
                    draggingFullRow = true;
                    // Toggle entire row selection
                    if (lastClickedRow == row && frozenTable.getSelectedRow() == row) {
                        // Clicking same ID again - deselect
                        EditorUtils.setFullRowSelectionActive(frozenTable, false);
                        frozenTable.clearSelection();
                        frozenTable.getColumnModel().getSelectionModel().clearSelection();
                        EditorUtils.runWithFrozenSyncSuppressed(mainTable, () -> {
                            mainTable.clearSelection();
                            mainTable.getColumnModel().getSelectionModel().clearSelection();
                        });
                        lastClickedRow = -1;
                    } else {
                        // Select entire row
                        EditorUtils.setFullRowSelectionActive(frozenTable, true);
                        frozenTable.setRowSelectionInterval(row, row);
                        if (frozenTable.getColumnCount() > 0) {
                            frozenTable.setColumnSelectionInterval(0, frozenTable.getColumnCount() - 1);
                        }
                        EditorUtils.runWithFrozenSyncSuppressed(mainTable, () -> {
                            mainTable.setRowSelectionInterval(row, row);
                            if (mainTable.getColumnCount() > 0) {
                                mainTable.setColumnSelectionInterval(0, mainTable.getColumnCount() - 1);
                            } else {
                                mainTable.getColumnModel().getSelectionModel().clearSelection();
                            }
                        });
                        lastClickedRow = row;
                    }
                    e.consume(); // Prevent default cell selection
                } else {
                    draggingFullRow = false;
                    EditorUtils.setFullRowSelectionActive(frozenTable, false);
                    lastClickedRow = -1;
                }
            }

            @Override
            public void mouseDragged(java.awt.event.MouseEvent e) {
                if (!draggingFullRow) {
                    return;
                }
                int row = frozenTable.rowAtPoint(e.getPoint());
                if (row < 0) {
                    return;
                }
                if (row != frozenTable.getSelectedRow()) {
                    EditorUtils.setFullRowSelectionActive(frozenTable, true);
                    frozenTable.setRowSelectionInterval(row, row);
                    if (frozenTable.getColumnCount() > 0) {
                        frozenTable.setColumnSelectionInterval(0, frozenTable.getColumnCount() - 1);
                    }
                    EditorUtils.runWithFrozenSyncSuppressed(mainTable, () -> {
                        mainTable.setRowSelectionInterval(row, row);
                        if (mainTable.getColumnCount() > 0) {
                            mainTable.setColumnSelectionInterval(0, mainTable.getColumnCount() - 1);
                        } else {
                            mainTable.getColumnModel().getSelectionModel().clearSelection();
                        }
                    });
                    lastClickedRow = row;
                }
            }

            @Override
            public void mouseReleased(java.awt.event.MouseEvent e) {
                draggingFullRow = false;
            }
        };
        frozenTable.addMouseListener(frozenRowSelector);
        frozenTable.addMouseMotionListener(frozenRowSelector);

        // Main table
        TableModel mainModel = new AbstractTableModel() {
            public int getRowCount() {
                return tableModel.getRowCount();
            }

            public int getColumnCount() {
                return tableModel.getColumnCount() - 2;
            }

            public Object getValueAt(int row, int col) {
                return tableModel.getValueAt(row, col + 2);
            }

            public void setValueAt(Object val, int row, int col) {
                tableModel.setValueAt(val, row, col + 2);
            }

            public String getColumnName(int col) {
                return tableModel.getColumnName(col + 2);
            }

            public Class<?> getColumnClass(int col) {
                return tableModel.getColumnClass(col + 2);
            }

            public boolean isCellEditable(int row, int col) {
                if (copyPasteModeEnabled) {
                    return false;
                }
                return tableModel.isCellEditable(row, col + 2);
            }
        };

        mainTable = new JTable(mainModel);
        styleTable(mainTable, false);
        setupMainTableColumns();
        TableLayoutDefaults.refreshHeaderPreferredWidth(mainTable);
        TableLayoutDefaults.applyRowHeight(mainTable, iconCache.hasIcons());

        // Sync row selection model between tables
        frozenTable.setSelectionModel(mainTable.getSelectionModel());
        frozenTable.getColumnModel().getSelectionModel().setSelectionMode(ListSelectionModel.SINGLE_INTERVAL_SELECTION);
        mainTable.getColumnModel().getSelectionModel().setSelectionMode(ListSelectionModel.SINGLE_INTERVAL_SELECTION);
        EditorUtils.installFrozenColumnSync(frozenTable, mainTable);

        JScrollPane frozenScrollPane = new JScrollPane(frozenTable);
        frozenScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_NEVER);
        frozenScrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        frozenScrollPane.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, EditorTheme.border()));
        frozenScrollPane.setColumnHeaderView(frozenTable.getTableHeader());
        frozenScrollPane.getViewport().setBackground(EditorTheme.surface());

        JScrollPane mainScrollPane = new JScrollPane(mainTable);
        mainScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        mainScrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        mainScrollPane.setColumnHeaderView(mainTable.getTableHeader());
        mainScrollPane.setBorder(BorderFactory.createEmptyBorder());
        mainScrollPane.getViewport().setBackground(EditorTheme.surface());
        EditorUtils.installHeaderViewportSync(mainScrollPane);
        EditorUtils.linkVerticalScrollBars(frozenScrollPane, mainScrollPane);

        JPanel leftPanel = new JPanel(new BorderLayout());
        leftPanel.add(frozenScrollPane, BorderLayout.CENTER);
        int frozenWidth = TableLayoutDefaults.frozenPanelWidth(iconCache.hasIcons());
        leftPanel.setPreferredSize(new Dimension(frozenWidth, 0));
        leftPanel.setMinimumSize(new Dimension(frozenWidth, 0));
        EditorUtils.addHorizontalScrollbarSpacer(leftPanel, mainScrollPane);

        panel.add(leftPanel, BorderLayout.WEST);
        panel.add(mainScrollPane, BorderLayout.CENTER);

        return panel;
    }

    private void stopEditing() {
        if (mainTable != null && mainTable.isEditing()) {
            mainTable.getCellEditor().stopCellEditing();
        }
        if (frozenTable != null && frozenTable.isEditing()) {
            frozenTable.getCellEditor().stopCellEditing();
        }
    }

    private void createBackup() {
        evolutionsBackup.clear();
        for (Species species : pokemonList) {
            if (species == null) {
                continue;
            }
            List<Evolution> copy = new java.util.ArrayList<>();
            for (Evolution evo : species.getEvolutionsFrom()) {
                copy.add(new Evolution(evo));
            }
            evolutionsBackup.put(species, copy);
        }
    }

    private void restoreFromBackup() {
        for (Species species : pokemonList) {
            if (species == null) {
                continue;
            }
            List<Evolution> backup = evolutionsBackup.get(species);
            List<Evolution> current = species.getEvolutionsFrom();
            current.clear();
            if (backup != null) {
                for (Evolution evo : backup) {
                    current.add(new Evolution(evo));
                }
            }
        }
        tableModel.fireTableDataChanged();
        if (frozenTable != null) {
            frozenTable.repaint();
        }
        if (mainTable != null) {
            mainTable.repaint();
        }
    }

    private void commitChanges() {
        createBackup();
    }

    public void onWindowClosing() {
        stopEditing();
        restoreFromBackup();
    }

    @Override
    public void removeNotify() {
        // Defensive cleanup for the randomize/new-ROM path: closeOpenEditorFrames()
        // calls frame.dispose() directly, which does NOT fire windowClosing, so
        // onWindowClosing()/restoreFromBackup() never runs. A row left mid-edit
        // ("Evolves To" set, "Method" not yet chosen) leaves a phantom
        // EvolutionType.NONE on the live Species that would otherwise be serialized
        // into the randomized/saved ROM. dispose() removes this panel from its frame,
        // firing removeNotify(), so strip the NONE placeholders (preserving real,
        // already-applied edits) and rebuild the reverse links before we go away.
        stopEditing();
        EditorUtils.stripNoneEvolutions(romHandler.getSpeciesInclFormes());
        EditorUtils.rebuildEvolutionReverseLinks(romHandler.getSpeciesInclFormes());
        super.removeNotify();
    }

    private void styleTable(JTable table, boolean isFrozen) {
        if (isFrozen) {
            TableLayoutDefaults.applySheetTableStyle(table, true, 1);
        } else {
            TableLayoutDefaults.applySheetTableStyle(table, false);
        }
    }

    private void setupMainTableColumns() {
        // Create dynamic parameter editor
        DynamicParameterEditor paramEditor = new DynamicParameterEditor(mainTable, pokemonList, itemList, moveList);

        for (int i = 0; i < mainTable.getColumnCount(); i++) {
            TableColumn column = mainTable.getColumnModel().getColumn(i);
            int mod = i % 3;

            if (mod == 0) {
                // Evolves To column
                column.setPreferredWidth(iconCache.hasIcons() ? 160 : 130);
                column.setCellEditor(new SpeciesComboBoxEditor(pokemonList));
                if (iconCache.hasIcons()) {
                    column.setCellRenderer(new EvolutionTargetRenderer());
                }
            } else if (mod == 1) {
                // Method column
                column.setPreferredWidth(180);
                column.setCellEditor(new MethodComboBoxEditor());
            } else {
                // Parameter column - dynamic based on method
                column.setPreferredWidth(130);
                column.setCellEditor(paramEditor);
            }
        }
    }

    private void exportToCSV() {
        stopEditing();
        EditorUtils.exportTableToCSV(this, tableModel, "Evolutions Sheet");
    }

    private void importFromCSV() {
        if (copyPasteModeEnabled) {
            JOptionPane.showMessageDialog(this,
                    "Disable Copy/Paste Mode before importing.",
                    "Import CSV",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        stopEditing();

        EditorUtils.CsvData csvData = EditorUtils.chooseCsvFile(this, "Evolutions Sheet");
        if (csvData == null) {
            return;
        }

        try {
            int applied = EditorUtils.applyCsvDataToTable(csvData.getRows(), tableModel, true, this);
            tableModel.fireTableDataChanged();
            if (frozenTable != null) {
                frozenTable.repaint();
            }
            if (mainTable != null) {
                mainTable.repaint();
            }
            JOptionPane.showMessageDialog(this,
                    String.format("Imported %d rows from %s.", applied, csvData.getFile().getName()),
                    "Import Complete",
                    JOptionPane.INFORMATION_MESSAGE);
        } catch (IllegalArgumentException ex) {
            JOptionPane.showMessageDialog(this,
                    ex.getMessage(),
                    "Import Error",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    private void toggleCopyPasteMode(boolean enabled) {
        copyPasteModeEnabled = enabled;
        if (enabled) {
            if (mainTable.isEditing()) {
                mainTable.getCellEditor().stopCellEditing();
            }
            if (frozenTable.isEditing()) {
                frozenTable.getCellEditor().stopCellEditing();
            }
            JOptionPane.showMessageDialog(this,
                    "Copy/Paste Mode ON\n\n" +
                            "- Tables are now read-only\n" +
                            "- Select cells and press Ctrl+C to copy evolution data\n" +
                            "- Toggle off to resume editing",
                    "Copy/Paste Mode",
                    JOptionPane.INFORMATION_MESSAGE);
        }
        frozenTable.repaint();
        mainTable.repaint();
    }

    private void showFindDialog() {
        stopEditing();
        EditorUtils.FindOptions options = EditorUtils.showFindDialog(this, findState.getLastOptions());
        if (options == null) {
            return;
        }
        EditorUtils.performFind(this, frozenTable, mainTable, tableModel, 2, findState, options);
    }

    public void save() {
        stopEditing();
        // Drop any phantom EvolutionType.NONE evolutions before they persist, then
        // rebuild the reverse links so they are consistent with the cleaned data.
        EditorUtils.stripNoneEvolutions(romHandler.getSpeciesInclFormes());
        EditorUtils.rebuildEvolutionReverseLinks(romHandler.getSpeciesInclFormes());
        ManualEditRegistry.getInstance().addEntries("Evolutions", collectEvolutionChangesForLog());

        if (!EditorUtils.suppressSaveDialogs) {
            JOptionPane.showMessageDialog(this,
                    "- Evolutions updated successfully!\n\nChanges will be saved when you save/randomize the ROM.",
                    "Save Complete",
                    JOptionPane.INFORMATION_MESSAGE);
        }
        commitChanges();
    }

    private List<String> collectEvolutionChangesForLog() {
        List<String> changes = new ArrayList<>();
        for (Species species : pokemonList) {
            if (species == null) {
                continue;
            }
            List<Evolution> before = evolutionsBackup.get(species);
            List<Evolution> after = species.getEvolutionsFrom();
            if (!evolutionListsEqual(before, after)) {
                changes.add(species.getFullName() + ": evolutions updated");
            }
        }
        return changes;
    }

    private boolean evolutionListsEqual(List<Evolution> a, List<Evolution> b) {
        if (a == null || a.isEmpty()) {
            return b == null || b.isEmpty();
        }
        if (b == null || a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!Objects.equals(a.get(i), b.get(i))) {
                return false;
            }
        }
        return true;
    }

    private void reload() {
        int result = JOptionPane.showConfirmDialog(this,
                "Reload evolutions from ROM?\nAny unsaved changes will be lost.",
                "Confirm Reload",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE);

        if (result == JOptionPane.YES_OPTION) {
            stopEditing();
            restoreFromBackup();
        }
    }

    private class BaseSpeciesRenderer extends TableLayoutDefaults.StripedCellRenderer {
        BaseSpeciesRenderer() {
            super(true, 1);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                boolean isSelected, boolean hasFocus, int row, int column) {
            Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            if (iconCache.hasIcons()) {
                setIcon(iconCache.getIcon(resolveBaseSpecies(table, row)));
            } else {
                setIcon(null);
            }
            setText(value == null ? "" : value.toString());
            return c;
        }

        private Species resolveBaseSpecies(JTable table, int viewRow) {
            int modelRow = table.convertRowIndexToModel(viewRow);
            int index = modelRow + 1;
            if (index >= 0 && index < pokemonList.size()) {
                return pokemonList.get(index);
            }
            return null;
        }
    }

    private class EvolutionTargetRenderer extends TableLayoutDefaults.StripedCellRenderer {
        EvolutionTargetRenderer() {
            super(false);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                boolean isSelected, boolean hasFocus, int row, int column) {
            Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            setHorizontalAlignment(SwingConstants.LEFT);
            if (iconCache.hasIcons()) {
                setIcon(iconCache.getIcon(parseSpecies(value)));
            } else {
                setIcon(null);
            }
            setText(value == null ? "" : value.toString());
            return c;
        }

        private Species parseSpecies(Object value) {
            if (value == null) {
                return null;
            }
            String text = value.toString();
            int colonIndex = text.indexOf(':');
            if (colonIndex > 0) {
                try {
                    int speciesId = Integer.parseInt(text.substring(0, colonIndex).trim());
                    return speciesByNumber.get(speciesId);
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
            return null;
        }
    }

    /**
     * ComboBox editor for Pokemon species
     */
    private static class SpeciesComboBoxEditor extends DefaultCellEditor {
        public SpeciesComboBoxEditor(List<Species> species) {
            super(new JComboBox<String>());
            JComboBox<String> comboBox = (JComboBox<String>) getComponent();
            comboBox.setFont(new Font("Segoe UI", Font.PLAIN, 11));
            comboBox.addItem("0: (None)");
            for (Species sp : species) {
                if (sp != null) {
                    comboBox.addItem(sp.getNumber() + ": " + sp.getName());
                }
            }
            EditorUtils.installSearchableComboBox(comboBox);
        }
    }

    /**
     * ComboBox editor for evolution methods
     */
    private static class MethodComboBoxEditor extends DefaultCellEditor {
        // EXACT evolution method names from PokEditor's sheet_strings.properties
        // Matching EvolutionsTable.java line 26 evolution method keys
        public MethodComboBoxEditor() {
            super(new JComboBox<String>());
            JComboBox<String> comboBox = (JComboBox<String>) getComponent();
            comboBox.setFont(new Font("Segoe UI", Font.PLAIN, 11));

            // Evolution methods 0-26 matching PokEditor EXACTLY
            comboBox.addItem("None");
            comboBox.addItem("Level-Up (Happiness)");
            comboBox.addItem("Level-Up (Happiness) (Day)");
            comboBox.addItem("Level-Up (Happiness) (Night)");
            comboBox.addItem("Reach Level");
            comboBox.addItem("Trade");
            comboBox.addItem("Trade (Item)");
            comboBox.addItem("Use Item");
            comboBox.addItem("Reach Level (Attack > Defense)");
            comboBox.addItem("Reach Level (Attack = Defense)");
            comboBox.addItem("Reach Level (Attack < Defense)");
            comboBox.addItem("Reach Level (PID < 5)");
            comboBox.addItem("Reach Level (PID > 5)");
            comboBox.addItem("Reach Level (Nincada) (1 of 2)");
            comboBox.addItem("Reach Level (Nincada) (2 of 2)");
            comboBox.addItem("Level-Up (Max Beauty)");
            comboBox.addItem("Use Item (Male)");
            comboBox.addItem("Use Item (Female)");
            comboBox.addItem("Level-Up (Held Item) (Day)");
            comboBox.addItem("Level-Up (Held Item) (Night)");
            comboBox.addItem("Level-Up (Move Known)");
            comboBox.addItem("Level-Up (Species in Party)");
            comboBox.addItem("Reach Level (Male)");
            comboBox.addItem("Reach Level (Female)");
            comboBox.addItem("Level-Up (Electric Field)");
            comboBox.addItem("Level-Up (Mossy Rock)");
            comboBox.addItem("Level-Up (Icy Rock)");
            EditorUtils.installSearchableComboBox(comboBox);
        }
    }

    /**
     * Dynamic editor for evolution parameters
     * Changes editor type based on the Method in the previous column
     */
    private static class DynamicParameterEditor extends AbstractCellEditor implements TableCellEditor {
        private final JTable mainTable;
        private final List<Species> pokemonList;
        private final List<Item> itemList;
        private final List<Move> moveList;

        private JComboBox<String> currentComboBox;
        private JTextField currentTextField;
        private JSpinner currentSpinner;

        public DynamicParameterEditor(JTable mainTable, List<Species> pokemonList, List<Item> itemList,
                List<Move> moveList) {
            this.mainTable = mainTable;
            this.pokemonList = pokemonList;
            this.itemList = itemList;
            this.moveList = moveList;
        }

        @Override
        public Component getTableCellEditorComponent(JTable table, Object value, boolean isSelected, int row,
                int column) {
            // Get the method from the previous column (column - 1 in the main table model)
            int methodCol = column - 1;
            Object methodValue = table.getValueAt(row, methodCol);

            String methodStr = methodValue != null ? methodValue.toString() : "";
            EvolutionType type = parseEvolutionType(methodStr);

            if (type == null || type == EvolutionType.NONE) {
                // No evolution - show disabled text field
                currentTextField = new JTextField("N/A");
                currentTextField.setEnabled(false);
                currentSpinner = null;
                currentComboBox = null;
                return currentTextField;
            }

            if (type.usesItem()) {
                // Item-based evolution
                currentComboBox = new JComboBox<String>();
                currentComboBox.setFont(new Font("Segoe UI", Font.PLAIN, 11));
                for (Item item : itemList) {
                    if (item != null) {
                        currentComboBox.addItem(item.getName());
                    }
                }
                EditorUtils.installSearchableComboBox(currentComboBox);
                currentComboBox.setSelectedItem(value);
                currentSpinner = null;
                currentTextField = null;
                return currentComboBox;

            } else if (type.usesMove()) {
                // Move-based evolution
                currentComboBox = new JComboBox<String>();
                currentComboBox.setFont(new Font("Segoe UI", Font.PLAIN, 11));
                for (Move move : moveList) {
                    if (move != null) {
                        currentComboBox.addItem(move.name);
                    }
                }
                EditorUtils.installSearchableComboBox(currentComboBox);
                currentComboBox.setSelectedItem(value);
                currentSpinner = null;
                currentTextField = null;
                return currentComboBox;

            } else if (type.usesSpecies()) {
                // Species-based evolution (e.g., Mantyke with Remoraid)
                currentComboBox = new JComboBox<String>();
                currentComboBox.setFont(new Font("Segoe UI", Font.PLAIN, 11));
                for (Species sp : pokemonList) {
                    if (sp != null) {
                        currentComboBox.addItem(sp.getName());
                    }
                }
                EditorUtils.installSearchableComboBox(currentComboBox);
                currentComboBox.setSelectedItem(value);
                currentSpinner = null;
                currentTextField = null;
                return currentComboBox;

            } else if (type.usesLevel()) {
                int initialLevel = extractLevelValue(value);
                currentSpinner = new JSpinner(new SpinnerNumberModel(initialLevel, 1, 100, 1));
                JSpinner.NumberEditor numberEditor = new JSpinner.NumberEditor(currentSpinner, "##");
                currentSpinner.setEditor(numberEditor);
                currentComboBox = null;
                currentTextField = null;
                return currentSpinner;

            } else {
                // Other types - N/A
                currentTextField = new JTextField("N/A");
                currentTextField.setEnabled(false);
                currentSpinner = null;
                currentComboBox = null;
                return currentTextField;
            }
        }

        @Override
        public Object getCellEditorValue() {
            if (currentSpinner != null) {
                return currentSpinner.getValue().toString();
            } else if (currentComboBox != null && currentComboBox.isEnabled()) {
                return currentComboBox.getSelectedItem();
            } else if (currentTextField != null && currentTextField.isEnabled()) {
                return currentTextField.getText();
            }
            return "N/A";
        }

        private int extractLevelValue(Object value) {
            if (value == null) {
                return 1;
            }
            String numeric = value.toString().replaceAll("[^0-9]", "");
            if (numeric.isEmpty()) {
                return 1;
            }
            try {
                int parsed = Integer.parseInt(numeric);
                return Math.min(100, Math.max(1, parsed));
            } catch (NumberFormatException ex) {
                return 1;
            }
        }

        // Parse PokEditor display names to EvolutionType
        private EvolutionType parseEvolutionType(String methodStr) {
            if (methodStr == null || methodStr.isEmpty() || methodStr.equals("None"))
                return null;

            // Use the same mapping as in EvolutionsTableModel
            switch (methodStr) {
                case "Level-Up (Happiness)":
                    return EvolutionType.HAPPINESS;
                case "Level-Up (Happiness) (Day)":
                    return EvolutionType.HAPPINESS_DAY;
                case "Level-Up (Happiness) (Night)":
                    return EvolutionType.HAPPINESS_NIGHT;
                case "Reach Level":
                    return EvolutionType.LEVEL;
                case "Trade":
                    return EvolutionType.TRADE;
                case "Trade (Item)":
                    return EvolutionType.TRADE_ITEM;
                case "Use Item":
                    return EvolutionType.STONE;
                case "Reach Level (Attack > Defense)":
                    return EvolutionType.LEVEL_ATTACK_HIGHER;
                case "Reach Level (Attack = Defense)":
                    return EvolutionType.LEVEL_ATK_DEF_SAME;
                case "Reach Level (Attack < Defense)":
                    return EvolutionType.LEVEL_DEFENSE_HIGHER;
                case "Reach Level (PID < 5)":
                    return EvolutionType.LEVEL_LOW_PV;
                case "Reach Level (PID > 5)":
                    return EvolutionType.LEVEL_HIGH_PV;
                case "Reach Level (Nincada) (1 of 2)":
                    return EvolutionType.LEVEL_CREATE_EXTRA;
                case "Reach Level (Nincada) (2 of 2)":
                    return EvolutionType.LEVEL_IS_EXTRA;
                case "Level-Up (Max Beauty)":
                    return EvolutionType.LEVEL_HIGH_BEAUTY;
                case "Use Item (Male)":
                    return EvolutionType.STONE_MALE_ONLY;
                case "Use Item (Female)":
                    return EvolutionType.STONE_FEMALE_ONLY;
                case "Level-Up (Held Item) (Day)":
                    return EvolutionType.LEVEL_ITEM_DAY;
                case "Level-Up (Held Item) (Night)":
                    return EvolutionType.LEVEL_ITEM_NIGHT;
                case "Level-Up (Move Known)":
                    return EvolutionType.LEVEL_WITH_MOVE;
                case "Level-Up (Species in Party)":
                    return EvolutionType.LEVEL_WITH_OTHER;
                case "Reach Level (Male)":
                    return EvolutionType.LEVEL_MALE_ONLY;
                case "Reach Level (Female)":
                    return EvolutionType.LEVEL_FEMALE_ONLY;
                case "Level-Up (Electric Field)":
                    return EvolutionType.LEVEL_MAGNETIC_FIELD;
                case "Level-Up (Mossy Rock)":
                    return EvolutionType.LEVEL_MOSS_ROCK;
                case "Level-Up (Icy Rock)":
                    return EvolutionType.LEVEL_ICE_ROCK;
                default:
                    return null;
            }
        }
    }

    /**
     * Table model for evolutions with proper edit support
     */
    private static class EvolutionsTableModel extends AbstractTableModel {
        private final List<Species> pokemonList;
        private final List<Move> moveList;
        private final List<Item> itemList;
        private final int maxEvos;
        private final RomHandler romHandler;
        private final String[] columnNames;

        public EvolutionsTableModel(List<Species> pokemonList, List<Move> moveList, List<Item> itemList,
                int maxEvos, RomHandler romHandler) {
            this.pokemonList = pokemonList;
            this.moveList = moveList;
            this.itemList = itemList;
            this.maxEvos = maxEvos;
            this.romHandler = romHandler;

            columnNames = new String[2 + maxEvos * 3];
            columnNames[0] = "ID";
            columnNames[1] = "Name";
            for (int i = 0; i < maxEvos; i++) {
                columnNames[2 + i * 3] = "Evolves To " + (i + 1);
                columnNames[2 + i * 3 + 1] = "Method " + (i + 1);
                columnNames[2 + i * 3 + 2] = "Parameter " + (i + 1);
            }
        }

        @Override
        public int getRowCount() {
            return pokemonList.size() - 1;
        }

        @Override
        public int getColumnCount() {
            return columnNames.length;
        }

        @Override
        public String getColumnName(int column) {
            return columnNames[column];
        }

        @Override
        public Class<?> getColumnClass(int col) {
            return String.class;
        }

        @Override
        public boolean isCellEditable(int row, int col) {
            return col > 1;
        }

        @Override
        public Object getValueAt(int row, int col) {
            Species pokemon = pokemonList.get(row + 1);
            if (pokemon == null)
                return "";

            if (col == 0)
                return pokemon.getNumber();
            if (col == 1)
                return pokemon.getName();

            List<Evolution> evos = pokemon.getEvolutionsFrom();
            int evoIndex = (col - 2) / 3;
            if (evoIndex >= evos.size())
                return "";

            Evolution evo = evos.get(evoIndex);
            int mod = (col - 2) % 3;

            if (mod == 0) {
                // Evolves To
                Species to = evo.getTo();
                return to != null ? to.getNumber() + ": " + to.getName() : "0: (None)";
            } else if (mod == 1) {
                // Method - return ONLY the description, matching PokEditor exactly
                EvolutionType type = evo.getType();
                return getMethodDescription(type);
            } else {
                // Parameter
                return getParameterDescription(evo.getType(), evo.getExtraInfo());
            }
        }

        @Override
        public void setValueAt(Object value, int row, int col) {
            if (col <= 1)
                return;

            Species pokemon = pokemonList.get(row + 1);
            if (pokemon == null)
                return;

            List<Evolution> evos = pokemon.getEvolutionsFrom();
            int evoIndex = (col - 2) / 3;
            int mod = (col - 2) % 3;

            try {
                if (mod == 0) {
                    // Evolves To - check if user selected "0: (None)" to clear evolution
                    String valStr = value.toString();
                    String[] parts = valStr.split(":");
                    if (parts.length > 0) {
                        int speciesNum = Integer.parseInt(parts[0].trim());

                        // If "0: (None)" was selected, remove this evolution slot
                        if (speciesNum == 0) {
                            if (evoIndex < evos.size()) {
                                evos.remove(evoIndex);
                                // Refresh the entire row to update all evolution columns
                                fireTableRowsUpdated(row, row);
                            }
                            return;
                        }

                        // Otherwise, ensure we have enough evolution slots
                        while (evoIndex >= evos.size()) {
                            evos.add(new Evolution(pokemon, pokemon, EvolutionType.NONE, 0));
                        }

                        Evolution evo = evos.get(evoIndex);
                        if (speciesNum < pokemonList.size() && pokemonList.get(speciesNum) != null) {
                            evo.setTo(pokemonList.get(speciesNum));
                        }
                    }
                } else if (mod == 1) {
                    // Method column - legitimately creates a slot (the user is defining
                    // a new evolution here). Ensure the slot exists.
                    while (evoIndex >= evos.size()) {
                        evos.add(new Evolution(pokemon, pokemon, EvolutionType.NONE, 0));
                    }

                    Evolution evo = evos.get(evoIndex);

                    {
                        // Method - parse using PokEditor exact names
                        String valStr = value.toString();
                        EvolutionType type = parseMethodDescription(valStr);

                        evo.setType(type != null ? type : EvolutionType.NONE);

                        // If user selected "None", remove this evolution slot
                        if (type == null || valStr.equals("None")) {
                            evos.remove(evoIndex);
                            // Refresh the entire row to update all evolution columns
                            fireTableRowsUpdated(row, row);
                            return;
                        }
                    }
                } else {
                    // Parameter column - NEVER create an evolution slot. A parameter
                    // edit on a non-existent evolution (e.g. a no-op CSV round-trip)
                    // must not spawn a phantom EvolutionType.NONE entry.
                    if (evoIndex >= evos.size()) {
                        return;
                    }
                    Evolution evo = evos.get(evoIndex);
                    {
                        // Parameter
                        EvolutionType type = evo.getType();
                        if (type != null) {
                            if (type.usesLevel()) {
                                String numeric = value.toString().replaceAll("[^0-9]", "");
                                int parsed = numeric.isEmpty() ? 0 : Integer.parseInt(numeric);
                                if (parsed > 0) {
                                    parsed = Math.min(100, Math.max(1, parsed));
                                } else {
                                    parsed = 1;
                                }
                                evo.setExtraInfo(parsed);
                            } else if (type.usesItem()) {
                                // Find item index by name
                                for (int i = 0; i < itemList.size(); i++) {
                                    if (itemList.get(i) != null && itemList.get(i).getName().equals(value.toString())) {
                                        evo.setExtraInfo(i);
                                        break;
                                    }
                                }
                            } else if (type.usesMove()) {
                                // Find move index by name
                                for (int i = 0; i < moveList.size(); i++) {
                                    if (moveList.get(i) != null && moveList.get(i).name.equals(value.toString())) {
                                        evo.setExtraInfo(i);
                                        break;
                                    }
                                }
                            } else if (type.usesSpecies()) {
                                // Find species index by name
                                for (int i = 0; i < pokemonList.size(); i++) {
                                    if (pokemonList.get(i) != null
                                            && pokemonList.get(i).getName().equals(value.toString())) {
                                        evo.setExtraInfo(i);
                                        break;
                                    }
                                }
                            }
                        }
                    }
                }
                fireTableCellUpdated(row, col);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        // Convert EvolutionType to EXACT PokEditor display names
        // These match sheet_strings.properties from PokEditor
        private String getMethodDescription(EvolutionType type) {
            if (type == null)
                return "None";

            switch (type) {
                case HAPPINESS:
                    return "Level-Up (Happiness)";
                case HAPPINESS_DAY:
                    return "Level-Up (Happiness) (Day)";
                case HAPPINESS_NIGHT:
                    return "Level-Up (Happiness) (Night)";
                case LEVEL:
                    return "Reach Level";
                case TRADE:
                    return "Trade";
                case TRADE_ITEM:
                    return "Trade (Item)";
                case STONE:
                    return "Use Item"; // PokEditor calls stones "Use Item"
                case LEVEL_ATTACK_HIGHER:
                    return "Reach Level (Attack > Defense)";
                case LEVEL_ATK_DEF_SAME:
                    return "Reach Level (Attack = Defense)";
                case LEVEL_DEFENSE_HIGHER:
                    return "Reach Level (Attack < Defense)";
                case LEVEL_LOW_PV:
                    return "Reach Level (PID < 5)";
                case LEVEL_HIGH_PV:
                    return "Reach Level (PID > 5)";
                case LEVEL_CREATE_EXTRA:
                    return "Reach Level (Nincada) (1 of 2)";
                case LEVEL_IS_EXTRA:
                    return "Reach Level (Nincada) (2 of 2)";
                case LEVEL_HIGH_BEAUTY:
                    return "Level-Up (Max Beauty)";
                case STONE_MALE_ONLY:
                    return "Use Item (Male)";
                case STONE_FEMALE_ONLY:
                    return "Use Item (Female)";
                case LEVEL_ITEM_DAY:
                    return "Level-Up (Held Item) (Day)";
                case LEVEL_ITEM_NIGHT:
                    return "Level-Up (Held Item) (Night)";
                case LEVEL_WITH_MOVE:
                    return "Level-Up (Move Known)";
                case LEVEL_WITH_OTHER:
                    return "Level-Up (Species in Party)";
                case LEVEL_MALE_ONLY:
                    return "Reach Level (Male)";
                case LEVEL_FEMALE_ONLY:
                    return "Reach Level (Female)";
                case LEVEL_MAGNETIC_FIELD:
                    return "Level-Up (Electric Field)";
                case LEVEL_MOSS_ROCK:
                    return "Level-Up (Mossy Rock)";
                case LEVEL_ICE_ROCK:
                    return "Level-Up (Icy Rock)";
                default:
                    return "None";
            }
        }

        // Convert PokEditor display names to EvolutionType enum
        private EvolutionType parseMethodDescription(String desc) {
            if (desc == null || desc.equals("None"))
                return null;

            switch (desc) {
                case "Level-Up (Happiness)":
                    return EvolutionType.HAPPINESS;
                case "Level-Up (Happiness) (Day)":
                    return EvolutionType.HAPPINESS_DAY;
                case "Level-Up (Happiness) (Night)":
                    return EvolutionType.HAPPINESS_NIGHT;
                case "Reach Level":
                    return EvolutionType.LEVEL;
                case "Trade":
                    return EvolutionType.TRADE;
                case "Trade (Item)":
                    return EvolutionType.TRADE_ITEM;
                case "Use Item":
                    return EvolutionType.STONE;
                case "Reach Level (Attack > Defense)":
                    return EvolutionType.LEVEL_ATTACK_HIGHER;
                case "Reach Level (Attack = Defense)":
                    return EvolutionType.LEVEL_ATK_DEF_SAME;
                case "Reach Level (Attack < Defense)":
                    return EvolutionType.LEVEL_DEFENSE_HIGHER;
                case "Reach Level (PID < 5)":
                    return EvolutionType.LEVEL_LOW_PV;
                case "Reach Level (PID > 5)":
                    return EvolutionType.LEVEL_HIGH_PV;
                case "Reach Level (Nincada) (1 of 2)":
                    return EvolutionType.LEVEL_CREATE_EXTRA;
                case "Reach Level (Nincada) (2 of 2)":
                    return EvolutionType.LEVEL_IS_EXTRA;
                case "Level-Up (Max Beauty)":
                    return EvolutionType.LEVEL_HIGH_BEAUTY;
                case "Use Item (Male)":
                    return EvolutionType.STONE_MALE_ONLY;
                case "Use Item (Female)":
                    return EvolutionType.STONE_FEMALE_ONLY;
                case "Level-Up (Held Item) (Day)":
                    return EvolutionType.LEVEL_ITEM_DAY;
                case "Level-Up (Held Item) (Night)":
                    return EvolutionType.LEVEL_ITEM_NIGHT;
                case "Level-Up (Move Known)":
                    return EvolutionType.LEVEL_WITH_MOVE;
                case "Level-Up (Species in Party)":
                    return EvolutionType.LEVEL_WITH_OTHER;
                case "Reach Level (Male)":
                    return EvolutionType.LEVEL_MALE_ONLY;
                case "Reach Level (Female)":
                    return EvolutionType.LEVEL_FEMALE_ONLY;
                case "Level-Up (Electric Field)":
                    return EvolutionType.LEVEL_MAGNETIC_FIELD;
                case "Level-Up (Mossy Rock)":
                    return EvolutionType.LEVEL_MOSS_ROCK;
                case "Level-Up (Icy Rock)":
                    return EvolutionType.LEVEL_ICE_ROCK;
                default:
                    return null;
            }
        }

        private String getParameterDescription(EvolutionType type, int extraInfo) {
            if (type == null)
                return "";

            if (type.usesLevel()) {
                return "Level " + extraInfo;
            } else if (type.usesItem()) {
                Item item = extraInfo < itemList.size() ? itemList.get(extraInfo) : null;
                return item != null ? item.getName() : "Item #" + extraInfo;
            } else if (type.usesMove()) {
                Move move = extraInfo < moveList.size() ? moveList.get(extraInfo) : null;
                return move != null ? move.name : "Move #" + extraInfo;
            } else if (type.usesSpecies()) {
                Species species = extraInfo < pokemonList.size() ? pokemonList.get(extraInfo) : null;
                return species != null ? species.getName() : "Pokemon #" + extraInfo;
            } else if (type == EvolutionType.HAPPINESS ||
                    type == EvolutionType.HAPPINESS_DAY ||
                    type == EvolutionType.HAPPINESS_NIGHT ||
                    type == EvolutionType.TRADE) {
                return "N/A";
            } else {
                return String.valueOf(extraInfo);
            }
        }
    }
}
