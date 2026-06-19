package com.dabomstew.pkrandom.pokemon.editors;

import com.dabomstew.pkrandom.log.ManualEditRegistry;
import com.dabomstew.pkromio.gamedata.Move;
import com.dabomstew.pkromio.gamedata.Species;
import com.dabomstew.pkromio.romhandlers.RomHandler;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Move Tutor compatibility editor mirroring PokEditor's matrix view.
 * Displays a boolean matrix of Pokemon vs tutor moves.
 */
public class Gen5MoveTutorsSheetPanel extends JPanel {

    private final RomHandler romHandler;
    private final List<Species> pokemonList;
    private final List<Move> moveList;
    private final List<Integer> tutorMoves;
    private final Map<Species, boolean[]> tutorCompatibility;
    private final PokemonIconCache iconCache;

    private JTable frozenTable;
    private JTable mainTable;
    private MoveTutorTableModel tableModel;
    private boolean copyPasteModeEnabled = false;
    private final EditorUtils.FindState findState = new EditorUtils.FindState();
    private final Map<Species, boolean[]> compatibilityBackup = new HashMap<>();
    private JPanel tableContainer;
    // Feature #7: snapshot/revert for the "Edit Tutor Moves..." dialog, which applies immediately.
    private final EditorUtils.MoveListGuard tutorMovesGuard;

    public Gen5MoveTutorsSheetPanel(RomHandler romHandler) {
        this.romHandler = romHandler;
        this.pokemonList = romHandler.getSpeciesInclFormes();
        this.moveList = romHandler.getMoves();
        if (romHandler.hasMoveTutors()) {
            this.tutorMoves = romHandler.getMoveTutorMoves();
            this.tutorCompatibility = EditorDataCache.get(romHandler).getMoveTutorCompatibility();
        } else {
            this.tutorMoves = new ArrayList<>();
            this.tutorCompatibility = new HashMap<>();
        }
        this.iconCache = PokemonIconCache.get(romHandler);
        this.tutorMovesGuard = new EditorUtils.MoveListGuard(
                romHandler::getMoveTutorMoves, romHandler::setMoveTutorMoves);
        initializeUI();
        createBackup();
    }

    private void initializeUI() {
        setLayout(new BorderLayout());
        setBackground(EditorTheme.surface());

        add(createStyledToolbar(), BorderLayout.NORTH);
        tableContainer = createFrozenColumnTable();
        add(tableContainer, BorderLayout.CENTER);
    }

    /**
     * Opens the tutor move assignment dialog and, when changes are applied,
     * rebuilds the compatibility table so the column headers show the new move
     * names.
     */
    private void editTutorMoves() {
        stopEditing();
        boolean changed = EditorUtils.editMoveTutorMoves(this, romHandler);
        if (changed) {
            tutorMovesGuard.markDirty();
            rebuildTable();
        }
    }

    /**
     * Rebuilds the table area in place (the compatibility edits live in
     * {@code tutorCompatibility} and survive the rebuild) so the freshly-built
     * column headers reflect the current tutor move assignment.
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

        // Only offer tutor move reassignment when the ROM actually has editable tutors.
        if (romHandler.hasMoveTutors() && !tutorMoves.isEmpty()) {
            JButton editTutorMovesButton = EditorUtils.createStyledButton("Edit Tutor Moves...", new Color(123, 31, 162));
            editTutorMovesButton.addActionListener(e -> editTutorMoves());
            toolbar.add(Box.createHorizontalStrut(10));
            toolbar.add(editTutorMovesButton);
        }
        toolbar.add(Box.createHorizontalStrut(10));

        JLabel infoLabel = new JLabel(String.format("Move Tutor Compatibility - %d tutors", tutorMoves.size()));
        infoLabel.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        infoLabel.setForeground(EditorTheme.mutedText());
        toolbar.add(infoLabel);

        return toolbar;
    }

    private JPanel createFrozenColumnTable() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(EditorTheme.surface());

        tableModel = new MoveTutorTableModel(pokemonList, moveList, tutorMoves, tutorCompatibility);

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

            @Override
            public boolean isCellEditable(int rowIndex, int columnIndex) {
                return false;
            }
        };

        frozenTable = new JTable(frozenModel);
        mainTable = new JTable(new AbstractTableModel() {
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
        });

        styleTable(frozenTable, true);
        styleTable(mainTable, false);
        setupMainTableColumns();

        TableLayoutDefaults.configureFrozenColumns(frozenTable.getColumnModel(), iconCache.hasIcons());
        TableLayoutDefaults.applyRowHeight(frozenTable, iconCache.hasIcons());
        TableLayoutDefaults.applyRowHeight(mainTable, iconCache.hasIcons());
        TableLayoutDefaults.refreshHeaderPreferredWidth(mainTable);

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
        mainScrollPane.setColumnHeaderView(mainTable.getTableHeader());
        mainScrollPane.getViewport().setBackground(EditorTheme.surface());
        mainScrollPane.setBorder(BorderFactory.createEmptyBorder());
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

    private void styleTable(JTable table, boolean isFrozen) {
        if (isFrozen) {
            TableLayoutDefaults.applySheetTableStyle(table, true, 1);
            table.setColumnSelectionAllowed(false);
            TutorCellRenderer frozenRenderer = new TutorCellRenderer(true);
            table.setDefaultRenderer(Object.class, frozenRenderer);
            table.setDefaultRenderer(String.class, frozenRenderer);
            table.setDefaultRenderer(Integer.class, frozenRenderer);
            if (table.getColumnModel().getColumnCount() > 1) {
                table.getColumnModel().getColumn(1).setCellRenderer(new FrozenNameRenderer());
            }
        } else {
            TableLayoutDefaults.applySheetTableStyle(table, false);
            table.setDefaultEditor(Boolean.class, new CheckboxEditor());
            CheckboxRenderer checkboxRenderer = new CheckboxRenderer();
            table.setDefaultRenderer(Boolean.class, checkboxRenderer);
            TutorCellRenderer mainRenderer = new TutorCellRenderer(false);
            table.setDefaultRenderer(Object.class, mainRenderer);
            table.setDefaultRenderer(String.class, mainRenderer);
            table.setDefaultRenderer(Integer.class, mainRenderer);
        }
    }

    private void setupMainTableColumns() {
        TableColumnModel columnModel = mainTable.getColumnModel();
        for (int i = 0; i < columnModel.getColumnCount(); i++) {
            TableColumn column = columnModel.getColumn(i);
            column.setPreferredWidth(200);
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

    private void toggleCopyPasteMode(boolean enabled) {
        copyPasteModeEnabled = enabled;
        if (enabled) {
            stopEditing();
            JOptionPane.showMessageDialog(this,
                    "Copy/Paste Mode ON\n\n" +
                            "- Tables are now read-only\n" +
                            "- Select cells and press Ctrl+C to copy compatibility data\n" +
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

        ManualEditRegistry.getInstance().addEntries("Move Tutor Compatibility", collectTutorChangesForLog());

        romHandler.setMoveTutorCompatibility(tutorCompatibility);

        if (!EditorUtils.suppressSaveDialogs) {
            JOptionPane.showMessageDialog(this,
                    "- Move tutor compatibility updated successfully!\n\nChanges will be saved when you save/randomize the ROM.",
                    "Save Complete",
                    JOptionPane.INFORMATION_MESSAGE);
        }
        // Feature #7: tutor-move reassignment is now kept; re-baseline its revert snapshot.
        tutorMovesGuard.commit();
        commitChanges();
    }

    private void reload() {
        int result = JOptionPane.showConfirmDialog(this,
                "Reload move tutor data from ROM?\nAny unsaved changes will be lost.",
                "Confirm Reload",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE);

        if (result == JOptionPane.YES_OPTION) {
            stopEditing();
            // Feature #7: revert an unsaved tutor-move reassignment, then rebuild headers if it changed.
            boolean reverted = tutorMovesGuard.revertIfDirty();
            restoreFromBackup();
            if (reverted) {
                rebuildTable();
            }
        }
    }

    private void exportToCSV() {
        stopEditing();
        EditorUtils.exportTableToCSV(this, tableModel, "Move Tutors");
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

        EditorUtils.CsvData csvData = EditorUtils.chooseCsvFile(this, "Move Tutors");
        if (csvData == null) {
            return;
        }

        try {
            int applied = EditorUtils.applyCsvDataToTable(csvData.getRows(), tableModel, true);
            tableModel.fireTableDataChanged();
            frozenTable.repaint();
            mainTable.repaint();
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

    private List<String> collectTutorChangesForLog() {
        List<String> changes = new ArrayList<>();
        int tutorCount = tutorMoves.size();

        for (Species species : pokemonList) {
            if (species == null) {
                continue;
            }
            boolean[] beforeValues = compatibilityBackup.get(species);
            boolean[] afterValues = tutorCompatibility.get(species);
            if (beforeValues == null && afterValues == null) {
                continue;
            }

            boolean[] before = beforeValues != null ? beforeValues : new boolean[tutorCount + 1];
            boolean[] after = afterValues != null ? afterValues : new boolean[tutorCount + 1];

            List<String> additions = new ArrayList<>();
            List<String> removals = new ArrayList<>();

            for (int idx = 0; idx < tutorCount; idx++) {
                boolean beforeVal = idx + 1 < before.length && before[idx + 1];
                boolean afterVal = idx + 1 < after.length && after[idx + 1];
                if (beforeVal == afterVal) {
                    continue;
                }
                String label = formatTutorLabel(idx);
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
                String displayName = EditorUtils.speciesNameWithSuffix(species);
                changes.add(String.format("%s: %s", displayName, String.join("; ", segments)));
            }
        }

        return changes;
    }

    private String formatTutorLabel(int index) {
        int moveId = (index < tutorMoves.size()) ? tutorMoves.get(index) : -1;
        String moveName = "???";
        if (moveId >= 0 && moveId < moveList.size()) {
            Move move = moveList.get(moveId);
            if (move != null && move.name != null) {
                moveName = move.name;
            } else {
                moveName = "Move #" + moveId;
            }
        } else if (moveId >= 0) {
            moveName = "Move #" + moveId;
        }
        return String.format("Tutor %02d (%s)", index + 1, moveName);
    }

    private void createBackup() {
        compatibilityBackup.clear();
        for (Species species : pokemonList) {
            if (species == null) {
                continue;
            }
            boolean[] compat = tutorCompatibility.get(species);
            if (compat != null) {
                boolean[] copy = new boolean[compat.length];
                System.arraycopy(compat, 0, copy, 0, compat.length);
                compatibilityBackup.put(species, copy);
            }
        }
    }

    private void restoreFromBackup() {
        for (Species species : pokemonList) {
            if (species == null) {
                continue;
            }
            boolean[] backup = compatibilityBackup.get(species);
            boolean[] current = tutorCompatibility.get(species);
            if (backup != null && current != null) {
                System.arraycopy(backup, 0, current, 0, Math.min(backup.length, current.length));
            }
        }
        tableModel.fireTableDataChanged();
        frozenTable.repaint();
        mainTable.repaint();
    }

    private void commitChanges() {
        createBackup();
    }

    public void onWindowClosing() {
        stopEditing();
        // Feature #7: drop an unsaved tutor-move reassignment (the dialog applied it live).
        tutorMovesGuard.revertIfDirty();
        restoreFromBackup();
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
            Color base = row % 2 == 0 ? TableLayoutDefaults.evenRowColor() : TableLayoutDefaults.oddRowColor();
            if (isSelected) {
                setBackground(table.getSelectionBackground());
                setForeground(table.getSelectionForeground());
            } else {
                setBackground(base);
                setForeground(EditorTheme.text());
            }
            return this;
        }
    }

    private static class CheckboxEditor extends DefaultCellEditor {
        public CheckboxEditor() {
            super(new JCheckBox());
            JCheckBox checkBox = (JCheckBox) getComponent();
            checkBox.setHorizontalAlignment(SwingConstants.CENTER);
            checkBox.setOpaque(true);
        }

        @Override
        public Component getTableCellEditorComponent(JTable table, Object value, boolean isSelected, int row,
                int column) {
            JCheckBox checkBox = (JCheckBox) super.getTableCellEditorComponent(table, value, isSelected, row, column);
            if (value instanceof Boolean) {
                checkBox.setSelected((Boolean) value);
            }
            Color base = row % 2 == 0 ? TableLayoutDefaults.evenRowColor() : TableLayoutDefaults.oddRowColor();
            if (isSelected) {
                checkBox.setBackground(table.getSelectionBackground());
                checkBox.setForeground(table.getSelectionForeground());
            } else {
                checkBox.setBackground(base);
                checkBox.setForeground(EditorTheme.text());
            }
            return checkBox;
        }
    }

    private static class TutorCellRenderer extends TableLayoutDefaults.StripedCellRenderer {
        private final boolean frozen;

        TutorCellRenderer(boolean isFrozen) {
            super(isFrozen, isFrozen ? new int[] { 1 } : new int[0]);
            this.frozen = isFrozen;
            if (isFrozen) {
                setIconTextGap(10);
            }
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus,
                int row, int column) {
            Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            if (frozen && column == 0) {
                setHorizontalAlignment(SwingConstants.CENTER);
            } else if (frozen && column == 1) {
                setHorizontalAlignment(SwingConstants.LEFT);
            }
            if (!frozen || column != 1) {
                setIcon(null);
            }
            setText(value == null ? "" : value.toString());
            return c;
        }
    }

    private class FrozenNameRenderer extends TutorCellRenderer {
        FrozenNameRenderer() {
            super(true);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus,
                int row, int column) {
            Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            Species species = resolveSpecies(table, row);
            if (iconCache.hasIcons()) {
                setIcon(iconCache.getIcon(species));
            } else {
                setIcon(null);
            }
            setText(species == null ? "" : EditorUtils.speciesNameWithSuffix(species));
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

    private static class MoveTutorTableModel extends AbstractTableModel {
        private final List<Species> pokemonList;
        private final List<Move> moveList;
        private final List<Integer> tutorMoves;
        private final Map<Species, boolean[]> tutorCompatibility;
        private final String[] columnNames;

        MoveTutorTableModel(List<Species> pokemonList, List<Move> moveList,
                List<Integer> tutorMoves, Map<Species, boolean[]> tutorCompatibility) {
            this.pokemonList = pokemonList;
            this.moveList = moveList;
            this.tutorMoves = tutorMoves;
            this.tutorCompatibility = tutorCompatibility;

            columnNames = new String[2 + tutorMoves.size()];
            columnNames[0] = "ID";
            columnNames[1] = "Name";

            for (int i = 0; i < tutorMoves.size(); i++) {
                int moveId = tutorMoves.get(i);
                String moveName = "???";
                if (moveId >= 0 && moveId < moveList.size()) {
                    Move move = moveList.get(moveId);
                    if (move != null && move.name != null) {
                        moveName = move.name;
                    } else {
                        moveName = "Move #" + moveId;
                    }
                } else if (moveId >= 0) {
                    moveName = "Move #" + moveId;
                }
                columnNames[2 + i] = String.format("<html>Tutor %02d<br>%s</html>", i + 1, escapeHtml(moveName));
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
            return columnIndex <= 1 ? String.class : Boolean.class;
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return columnIndex > 1;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            Species species = pokemonList.get(rowIndex + 1);
            if (species == null) {
                return columnIndex <= 1 ? "" : Boolean.FALSE;
            }

            if (columnIndex == 0) {
                return species.getNumber();
            }
            if (columnIndex == 1) {
                return EditorUtils.speciesNameWithSuffix(species);
            }

            boolean[] flags = tutorCompatibility.get(species);
            int tutorIndex = columnIndex - 1;
            if (flags != null && tutorIndex < flags.length) {
                return flags[tutorIndex];
            }
            return Boolean.FALSE;
        }

        @Override
        public void setValueAt(Object aValue, int rowIndex, int columnIndex) {
            if (columnIndex <= 1) {
                return;
            }
            Species species = pokemonList.get(rowIndex + 1);
            if (species == null) {
                return;
            }
            boolean[] flags = tutorCompatibility.computeIfAbsent(species, k -> new boolean[tutorMoves.size() + 1]);
            int tutorIndex = columnIndex - 1;
            if (tutorIndex >= flags.length) {
                boolean[] expanded = new boolean[tutorMoves.size() + 1];
                System.arraycopy(flags, 0, expanded, 0, flags.length);
                flags = expanded;
                tutorCompatibility.put(species, flags);
            }
            flags[tutorIndex] = (Boolean) aValue;
        }

        private String escapeHtml(String value) {
            if (value == null) {
                return "";
            }
            return value.replace("&", "&amp;").replace("<", "&lt;")
                    .replace(">", "&gt;").replace("\"", "&quot;");
        }
    }
}
