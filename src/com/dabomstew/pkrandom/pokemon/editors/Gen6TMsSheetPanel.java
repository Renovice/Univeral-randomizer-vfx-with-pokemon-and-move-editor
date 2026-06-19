package com.dabomstew.pkrandom.pokemon.editors;

import com.dabomstew.pkrandom.log.ManualEditRegistry;
import com.dabomstew.pkromio.gamedata.Move;
import com.dabomstew.pkromio.gamedata.Species;
import com.dabomstew.pkromio.romhandlers.RomHandler;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableCellEditor;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumn;
import javax.swing.table.TableModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * TM/HM compatibility sheet for Gen 6 games.
 */
public class Gen6TMsSheetPanel extends JPanel {

    private final RomHandler romHandler;
    private final List<Species> pokemonList;
    private final List<Move> moveList;
    private final Map<Species, boolean[]> tmCompatibility;
    private final int tmCount;
    private final int hmCount;

    private JTable frozenTable;
    private JTable mainTable;
    private TMCompatibilityTableModel tableModel;
    private boolean copyPasteModeEnabled;
    private final EditorUtils.FindState findState = new EditorUtils.FindState();
    private final PokemonIconCache iconCache;
    private final Map<Species, boolean[]> compatibilityBackup = new HashMap<>();
    private JPanel tableContainer;
    // Feature #7: snapshot/revert for the "Edit TM Moves..." dialog, which applies immediately.
    private final EditorUtils.MoveListGuard tmMovesGuard;

    public Gen6TMsSheetPanel(RomHandler romHandler) {
        this.romHandler = romHandler;
        this.pokemonList = romHandler.getSpeciesInclFormes();
        this.moveList = romHandler.getMoves();
        this.tmCompatibility = EditorDataCache.get(romHandler).getTMHMCompatibility();
        this.tmCount = romHandler.getTMCount();
        this.hmCount = romHandler.getHMCount();
        this.iconCache = PokemonIconCache.get(romHandler);
        this.tmMovesGuard = new EditorUtils.MoveListGuard(romHandler::getTMMoves, romHandler::setTMMoves);
        initializeUI();
        createBackup();
    }

    private void initializeUI() {
        setLayout(new BorderLayout());
        setBackground(EditorTheme.surface());

        add(createToolbar(), BorderLayout.NORTH);
        tableContainer = createFrozenColumnTable();
        add(tableContainer, BorderLayout.CENTER);
    }

    /**
     * Opens the TM move assignment dialog and, when changes are applied, rebuilds
     * the compatibility table so the column headers show the new move names.
     */
    private void editTmMoves() {
        stopEditing();
        boolean changed = EditorUtils.editTMMoves(this, romHandler);
        if (changed) {
            tmMovesGuard.markDirty();
            rebuildTable();
        }
    }

    /**
     * Rebuilds the table area in place (the compatibility edits live in
     * {@code tmCompatibility} and survive the rebuild) so the freshly-built column
     * headers reflect the current TM move assignment.
     */
    private void rebuildTable() {
        if (tableContainer != null) {
            remove(tableContainer);
        }
        tableContainer = createFrozenColumnTable();
        add(tableContainer, BorderLayout.CENTER);
        revalidate();
        repaint();
    }

    private JPanel createToolbar() {
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

        JButton selectAllButton = EditorUtils.createStyledButton("Select All", new Color(3, 155, 229));
        selectAllButton.addActionListener(e -> selectAllForPokemon());

        JButton clearAllButton = EditorUtils.createStyledButton("Clear All", new Color(229, 57, 53));
        clearAllButton.addActionListener(e -> clearAllForPokemon());

        toolbar.add(saveButton);
        toolbar.add(reloadButton);
        toolbar.add(exportButton);
        toolbar.add(importButton);
        toolbar.add(Box.createHorizontalStrut(5));
        toolbar.add(copyPasteButton);
        toolbar.add(findButton);
        toolbar.add(Box.createHorizontalStrut(10));
        toolbar.add(selectAllButton);
        toolbar.add(clearAllButton);

        // Only offer TM move reassignment when this ROM exposes editable TM moves.
        if (!romHandler.getTMMoves().isEmpty()) {
            JButton editTmMovesButton = EditorUtils.createStyledButton("Edit TM Moves...", new Color(123, 31, 162));
            editTmMovesButton.addActionListener(e -> editTmMoves());
            toolbar.add(Box.createHorizontalStrut(10));
            toolbar.add(editTmMovesButton);
        }
        toolbar.add(Box.createHorizontalStrut(10));

        JLabel infoLabel = new JLabel(String.format("TM/HM Compatibility Matrix - %d TMs, %d HMs", tmCount, hmCount));
        infoLabel.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        infoLabel.setForeground(EditorTheme.mutedText());
        toolbar.add(infoLabel);

        return toolbar;
    }

    private JPanel createFrozenColumnTable() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(EditorTheme.surface());

        tableModel = new TMCompatibilityTableModel(pokemonList, moveList, tmCompatibility, tmCount, hmCount,
                romHandler);
        final boolean hasIcons = iconCache.hasIcons();

        TableModel frozenModel = new AbstractTableModel() {
            @Override
            public int getRowCount() {
                return tableModel.getRowCount();
            }

            @Override
            public int getColumnCount() {
                return 2;
            }

            @Override
            public Object getValueAt(int rowIndex, int columnIndex) {
                return tableModel.getValueAt(rowIndex, columnIndex);
            }

            @Override
            public String getColumnName(int column) {
                return tableModel.getColumnName(column);
            }

            @Override
            public Class<?> getColumnClass(int columnIndex) {
                return tableModel.getColumnClass(columnIndex);
            }
        };

        frozenTable = new JTable(frozenModel) {
            @Override
            public void scrollRectToVisible(Rectangle aRect) {
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
        TableLayoutDefaults.configureFrozenColumns(frozenTable.getColumnModel(), hasIcons);
        TableLayoutDefaults.applyRowHeight(frozenTable, hasIcons);
        if (hasIcons) {
            frozenTable.getColumnModel().getColumn(1).setCellRenderer(new FrozenNameRenderer());
        }

        java.awt.event.MouseAdapter rowSelector = new java.awt.event.MouseAdapter() {
            private int lastClickedRow = -1;
            private boolean draggingFullRow;

            @Override
            public void mousePressed(java.awt.event.MouseEvent e) {
                int row = frozenTable.rowAtPoint(e.getPoint());
                int col = frozenTable.columnAtPoint(e.getPoint());

                if (col == 0 && row >= 0) {
                    draggingFullRow = true;
                    if (lastClickedRow == row && frozenTable.getSelectedRow() == row) {
                        EditorUtils.setFullRowSelectionActive(frozenTable, false);
                        frozenTable.clearSelection();
                        frozenTable.getColumnModel().getSelectionModel().clearSelection();
                        EditorUtils.runWithFrozenSyncSuppressed(mainTable, () -> {
                            mainTable.clearSelection();
                            mainTable.getColumnModel().getSelectionModel().clearSelection();
                        });
                        lastClickedRow = -1;
                    } else {
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
                    e.consume();
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
        frozenTable.addMouseListener(rowSelector);
        frozenTable.addMouseMotionListener(rowSelector);

        TableModel mainModel = new AbstractTableModel() {
            @Override
            public int getRowCount() {
                return tableModel.getRowCount();
            }

            @Override
            public int getColumnCount() {
                return tableModel.getColumnCount() - 2;
            }

            @Override
            public Object getValueAt(int rowIndex, int columnIndex) {
                return tableModel.getValueAt(rowIndex, columnIndex + 2);
            }

            @Override
            public void setValueAt(Object aValue, int rowIndex, int columnIndex) {
                tableModel.setValueAt(aValue, rowIndex, columnIndex + 2);
            }

            @Override
            public String getColumnName(int column) {
                return tableModel.getColumnName(column + 2);
            }

            @Override
            public Class<?> getColumnClass(int columnIndex) {
                return tableModel.getColumnClass(columnIndex + 2);
            }

            @Override
            public boolean isCellEditable(int rowIndex, int columnIndex) {
                if (copyPasteModeEnabled) {
                    return false;
                }
                return tableModel.isCellEditable(rowIndex, columnIndex + 2);
            }
        };

        mainTable = new JTable(mainModel);
        styleTable(mainTable, false);
        setupMainTableColumns();
        TableLayoutDefaults.refreshHeaderPreferredWidth(mainTable);
        TableLayoutDefaults.applyRowHeight(mainTable, hasIcons);

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
        mainScrollPane.getViewport().setBackground(EditorTheme.surface());
        mainScrollPane.setBorder(BorderFactory.createEmptyBorder());
        EditorUtils.installHeaderViewportSync(mainScrollPane);

        EditorUtils.linkVerticalScrollBars(frozenScrollPane, mainScrollPane);

        JPanel leftPanel = new JPanel(new BorderLayout());
        leftPanel.add(frozenScrollPane, BorderLayout.CENTER);
        int frozenWidth = TableLayoutDefaults.frozenPanelWidth(hasIcons);
        leftPanel.setPreferredSize(new Dimension(frozenWidth, 0));
        leftPanel.setMinimumSize(new Dimension(frozenWidth, 0));
        EditorUtils.addHorizontalScrollbarSpacer(leftPanel, mainScrollPane);

        panel.add(leftPanel, BorderLayout.WEST);
        panel.add(mainScrollPane, BorderLayout.CENTER);

        return panel;
    }

    private void styleTable(JTable table, boolean isFrozen) {
        if (isFrozen) {
            TableLayoutDefaults.applySheetTableStyle(table, true, 1);
            table.setColumnSelectionAllowed(false);
        } else {
            TableLayoutDefaults.applySheetTableStyle(table, false);
        }

        TMCellRenderer renderer = new TMCellRenderer(isFrozen);
        table.setDefaultRenderer(Object.class, renderer);
        table.setDefaultRenderer(Integer.class, renderer);
        table.setDefaultRenderer(String.class, renderer);
        table.setDefaultRenderer(Boolean.class, renderer);
    }

    private void setupMainTableColumns() {
        for (int i = 0; i < mainTable.getColumnCount(); i++) {
            TableColumn column = mainTable.getColumnModel().getColumn(i);
            column.setPreferredWidth(150);
            column.setMinWidth(100);
            column.setMaxWidth(230);
            column.setWidth(150);
            column.setCellRenderer(new CheckboxRenderer());
            column.setCellEditor(new StableCheckBoxEditor());
        }
        mainTable.doLayout();
    }

    private void createBackup() {
        compatibilityBackup.clear();
        for (Species species : pokemonList) {
            if (species == null) {
                continue;
            }
            boolean[] compat = tmCompatibility.get(species);
            if (compat != null) {
                compatibilityBackup.put(species, compat.clone());
            }
        }
    }

    private void stopEditing() {
        if (mainTable != null && mainTable.isEditing()) {
            mainTable.getCellEditor().stopCellEditing();
        }
        if (frozenTable != null && frozenTable.isEditing()) {
            frozenTable.getCellEditor().stopCellEditing();
        }
    }

    public void onWindowClosing() {
        stopEditing();
        // Feature #7: drop an unsaved TM-move reassignment (the dialog applied it live).
        tmMovesGuard.revertIfDirty();
        restoreFromBackup();
    }

    private void restoreFromBackup() {
        for (Species species : pokemonList) {
            if (species == null) {
                continue;
            }
            boolean[] backup = compatibilityBackup.get(species);
            boolean[] current = tmCompatibility.get(species);
            if (backup != null && current != null) {
                System.arraycopy(backup, 0, current, 0, Math.min(backup.length, current.length));
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

    public void save() {
        stopEditing();

        ManualEditRegistry.getInstance().addEntries("TM/HM Compatibility", collectTmChangesForLog());
        romHandler.setTMHMCompatibility(tmCompatibility);

        if (!EditorUtils.suppressSaveDialogs) {
            JOptionPane.showMessageDialog(this,
                    "- TM/HM compatibility updated successfully!\n\nChanges will be saved when you save/randomize the ROM.",
                    "Save Complete",
                    JOptionPane.INFORMATION_MESSAGE);
        }
        // Feature #7: TM-move reassignment is now kept; re-baseline its revert snapshot.
        tmMovesGuard.commit();
        commitChanges();
    }

    private List<String> collectTmChangesForLog() {
        List<String> changes = new ArrayList<>();
        List<Integer> tmMoves = romHandler.getTMMoves();
        List<Integer> hmMoves = romHandler.getHMMoves();
        int totalColumns = tmCount + hmCount;

        Set<Integer> processedNumbers = new HashSet<>();
        for (Species species : pokemonList) {
            if (species == null) {
                continue;
            }
            if (!processedNumbers.add(species.getNumber())) {
                continue;
            }
            boolean[] before = compatibilityBackup.get(species);
            boolean[] after = tmCompatibility.get(species);
            if (before == null && after == null) {
                continue;
            }

            List<String> additions = new ArrayList<>();
            List<String> removals = new ArrayList<>();

            // Compatibility arrays are 1-indexed (index 0 unused), so diff 1..totalColumns
            for (int i = 1; i <= totalColumns; i++) {
                boolean beforeVal = before != null && i < before.length && before[i];
                boolean afterVal = after != null && i < after.length && after[i];
                if (beforeVal == afterVal) {
                    continue;
                }
                String label = formatLabel(i - 1, tmMoves, hmMoves);
                if (afterVal) {
                    additions.add(label);
                } else {
                    removals.add(label);
                }
            }

            if (!additions.isEmpty() || !removals.isEmpty()) {
                List<String> segments = new ArrayList<>();
                if (!additions.isEmpty()) {
                    segments.add("Added " + String.join(", ", additions));
                }
                if (!removals.isEmpty()) {
                    segments.add("Removed " + String.join(", ", removals));
                }
                changes.add(String.format("%s: %s",
                        EditorUtils.speciesNameWithSuffix(species),
                        String.join("; ", segments)));
            }
        }

        return changes;
    }

    private String formatLabel(int index, List<Integer> tmMoves, List<Integer> hmMoves) {
        if (index < tmCount) {
            String moveName = moveNameFromIndex(tmMoves, index);
            return String.format("TM%02d (%s)", index + 1, moveName);
        }
        int hmIndex = index - tmCount;
        String moveName = moveNameFromIndex(hmMoves, hmIndex);
        return String.format("HM%02d (%s)", hmIndex + 1, moveName);
    }

    private String moveNameFromIndex(List<Integer> moveIndices, int offset) {
        if (moveIndices == null || offset < 0 || offset >= moveIndices.size()) {
            return "???";
        }
        int moveIdx = moveIndices.get(offset);
        if (moveIdx >= 0 && moveIdx < moveList.size()) {
            Move move = moveList.get(moveIdx);
            if (move != null && move.name != null) {
                return move.name;
            }
        }
        return "Move #" + moveIdx;
    }

    private void reload() {
        int result = JOptionPane.showConfirmDialog(this,
                "Reload TM/HM data from ROM?\nAny unsaved changes will be lost.",
                "Confirm Reload",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE);

        if (result == JOptionPane.YES_OPTION) {
            stopEditing();
            // Feature #7: revert an unsaved TM-move reassignment, then rebuild headers if it changed.
            boolean reverted = tmMovesGuard.revertIfDirty();
            restoreFromBackup();
            if (reverted) {
                rebuildTable();
            }
        }
    }

    private void exportToCSV() {
        stopEditing();
        EditorUtils.exportTableToCSV(this, tableModel, "TM Compatibility Sheet");
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

        EditorUtils.CsvData csvData = EditorUtils.chooseCsvFile(this, "TM Compatibility Sheet");
        if (csvData == null) {
            return;
        }

        try {
            int applied = EditorUtils.applyCsvDataToTable(csvData.getRows(), tableModel, true);
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
                    "Copy/Paste Mode ON\n\n- Tables are now read-only\n- Select cells and press Ctrl+C to copy compatibility data\n- Toggle off to resume editing",
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

    private void selectAllForPokemon() {
        if (copyPasteModeEnabled) {
            JOptionPane.showMessageDialog(this,
                    "Copy/Paste Mode is enabled.\nDisable it to make changes.",
                    "Read-Only Mode",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        int selectedRow = mainTable.getSelectedRow();
        if (selectedRow < 0) {
            JOptionPane.showMessageDialog(this,
                    "Please select a Pokemon row first.",
                    "No Selection",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        for (int col = 2; col < tableModel.getColumnCount(); col++) {
            tableModel.setValueAt(true, selectedRow, col);
        }
        tableModel.fireTableRowsUpdated(selectedRow, selectedRow);
        mainTable.repaint();
    }

    private void clearAllForPokemon() {
        if (copyPasteModeEnabled) {
            JOptionPane.showMessageDialog(this,
                    "Copy/Paste Mode is enabled.\nDisable it to make changes.",
                    "Read-Only Mode",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        int selectedRow = mainTable.getSelectedRow();
        if (selectedRow < 0) {
            JOptionPane.showMessageDialog(this,
                    "Please select a Pokemon row first.",
                    "No Selection",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        for (int col = 2; col < tableModel.getColumnCount(); col++) {
            tableModel.setValueAt(false, selectedRow, col);
        }
        tableModel.fireTableRowsUpdated(selectedRow, selectedRow);
        mainTable.repaint();
    }

    private static class CheckboxRenderer extends JCheckBox implements TableCellRenderer {
        CheckboxRenderer() {
            setHorizontalAlignment(SwingConstants.CENTER);
            setOpaque(true);
            setBorderPainted(false);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus,
                int row, int column) {
            setSelected(value != null && (Boolean) value);
            Color baseColor = row % 2 == 0 ? TableLayoutDefaults.evenRowColor() : TableLayoutDefaults.oddRowColor();
            if (isSelected) {
                setBackground(table.getSelectionBackground());
                setForeground(table.getSelectionForeground());
            } else {
                setBackground(baseColor);
                setForeground(EditorTheme.text());
            }
            return this;
        }
    }

    private static class StableCheckBoxEditor extends AbstractCellEditor implements TableCellEditor {
        private final JCheckBox checkBox = new JCheckBox();

        StableCheckBoxEditor() {
            checkBox.setHorizontalAlignment(SwingConstants.CENTER);
        }

        @Override
        public Object getCellEditorValue() {
            return checkBox.isSelected();
        }

        @Override
        public Component getTableCellEditorComponent(JTable table, Object value, boolean isSelected, int row,
                int column) {
            if (value instanceof Boolean) {
                checkBox.setSelected((Boolean) value);
            } else {
                checkBox.setSelected(false);
            }
            Color baseColor = row % 2 == 0 ? TableLayoutDefaults.evenRowColor() : TableLayoutDefaults.oddRowColor();
            if (isSelected) {
                checkBox.setBackground(table.getSelectionBackground());
                checkBox.setForeground(table.getSelectionForeground());
            } else {
                checkBox.setBackground(baseColor);
                checkBox.setForeground(EditorTheme.text());
            }
            return checkBox;
        }
    }

    private static class TMCellRenderer extends TableLayoutDefaults.StripedCellRenderer {
        TMCellRenderer(boolean isFrozen) {
            super(isFrozen, isFrozen ? new int[] { 1 } : new int[0]);
        }
    }

    private class FrozenNameRenderer extends TMCellRenderer {
        FrozenNameRenderer() {
            super(true);
            setIconTextGap(10);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus,
                int row, int column) {
            Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            if (iconCache.hasIcons()) {
                setIcon(iconCache.getIcon(resolveSpecies(table, row)));
            } else {
                setIcon(null);
            }
            setText(value == null ? "" : value.toString());
            return c;
        }

        private Species resolveSpecies(JTable table, int viewRow) {
            int modelRow = table.convertRowIndexToModel(viewRow);
            int index = modelRow + 1;
            if (index >= 0 && index < pokemonList.size()) {
                return pokemonList.get(index);
            }
            return null;
        }
    }

    private static class TMCompatibilityTableModel extends AbstractTableModel {
        private final List<Species> pokemonList;
        private final List<Move> moveList;
        private final Map<Species, boolean[]> tmCompatibility;
        private final int tmCount;
        private final int hmCount;
        private final RomHandler romHandler;
        private final String[] columnNames;
        private final List<Integer> tmMoves;
        private final List<Integer> hmMoves;

        TMCompatibilityTableModel(List<Species> pokemonList, List<Move> moveList,
                Map<Species, boolean[]> tmCompatibility,
                int tmCount, int hmCount, RomHandler romHandler) {
            this.pokemonList = pokemonList;
            this.moveList = moveList;
            this.tmCompatibility = tmCompatibility;
            this.tmCount = tmCount;
            this.hmCount = hmCount;
            this.romHandler = romHandler;
            this.tmMoves = romHandler.getTMMoves();
            this.hmMoves = romHandler.getHMMoves();

            columnNames = new String[2 + tmCount + hmCount];
            columnNames[0] = "ID";
            columnNames[1] = "Name";

            for (int i = 0; i < tmCount; i++) {
                int moveIndex = tmMoves.get(i);
                String moveName = moveIndex < moveList.size() && moveList.get(moveIndex) != null
                        ? moveList.get(moveIndex).name
                        : "???";
                columnNames[2 + i] = String.format("TM%02d\n%s", i + 1, moveName);
            }

            for (int i = 0; i < hmCount; i++) {
                int moveIndex = hmMoves.get(i);
                String moveName = moveIndex < moveList.size() && moveList.get(moveIndex) != null
                        ? moveList.get(moveIndex).name
                        : "???";
                columnNames[2 + tmCount + i] = String.format("HM%02d\n%s", i + 1, moveName);
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
        public Class<?> getColumnClass(int columnIndex) {
            if (columnIndex <= 1) {
                return String.class;
            }
            return Boolean.class;
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return columnIndex > 1;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            Species pokemon = pokemonList.get(rowIndex + 1);
            if (pokemon == null) {
                return columnIndex <= 1 ? "" : Boolean.FALSE;
            }

            if (columnIndex == 0) {
                return pokemon.getNumber();
            }
            if (columnIndex == 1) {
                return EditorUtils.speciesNameWithSuffix(pokemon);
            }

            boolean[] compat = tmCompatibility.get(pokemon);
            if (compat != null && columnIndex - 1 < compat.length) {
                return compat[columnIndex - 1];
            }
            return Boolean.FALSE;
        }

        @Override
        public void setValueAt(Object aValue, int rowIndex, int columnIndex) {
            if (columnIndex <= 1) {
                return;
            }

            Species pokemon = pokemonList.get(rowIndex + 1);
            if (pokemon == null) {
                return;
            }

            boolean[] compat = tmCompatibility.get(pokemon);
            if (compat != null && columnIndex - 1 < compat.length) {
                compat[columnIndex - 1] = (Boolean) aValue;
                fireTableCellUpdated(rowIndex, columnIndex);
            }
        }
    }
}
