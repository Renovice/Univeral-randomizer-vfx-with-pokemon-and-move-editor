package com.dabomstew.pkrandom.pokemon.editors;

import com.dabomstew.pkrandom.log.ManualEditRegistry;
import com.dabomstew.pkromio.gamedata.Move;
import com.dabomstew.pkromio.gamedata.Species;
import com.dabomstew.pkromio.romhandlers.RomHandler;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableColumn;
import javax.swing.table.TableColumnModel;
import javax.swing.table.TableModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Egg move editor for Generation 5 games.
 * Mirrors the frozen-column spreadsheet look used by the level-up learnset
 * editor.
 */
public class Gen5EggMovesSheetPanel extends JPanel {

    private static final int MAX_EGG_MOVES = 32;

    private final RomHandler romHandler;
    private final List<Species> pokemonList;
    private final List<Move> moveList;
    private final Map<Integer, List<Integer>> eggMoves;
    private final Map<Integer, List<Integer>> eggMovesBackup = new HashMap<>();
    // Species numbers this panel itself has edited. Used so a Reload/restore only
    // reverts keys this panel touched, instead of clearing the entire shared cache
    // map (which would wipe edits made by other panels, e.g. the Card View).
    private final Set<Integer> touchedKeys = new HashSet<>();
    private final Map<String, Integer> moveLookup = new HashMap<>();
    private final PokemonIconCache iconCache;

    private JTable frozenTable;
    private JTable mainTable;
    private EggMovesTableModel tableModel;
    private boolean copyPasteModeEnabled = false;
    private final EditorUtils.FindState findState = new EditorUtils.FindState();

    public Gen5EggMovesSheetPanel(RomHandler romHandler) {
        this.romHandler = romHandler;
        this.pokemonList = romHandler.getSpeciesInclFormes();
        this.moveList = romHandler.getMoves();
        this.eggMoves = EditorDataCache.get(romHandler).getEggMoves();
        this.iconCache = PokemonIconCache.get(romHandler);
        buildMoveLookup();
        createBackup();
        initializeUI();
    }

    private void initializeUI() {
        setLayout(new BorderLayout());
        setBackground(EditorTheme.surface());

        add(createStyledToolbar(), BorderLayout.NORTH);
        add(createFrozenColumnTable(), BorderLayout.CENTER);
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

        JButton addSlotButton = EditorUtils.createStyledButton("Add Slot", new Color(63, 81, 181));
        addSlotButton.addActionListener(e -> addEggMoveSlot());

        JButton removeSlotButton = EditorUtils.createStyledButton("Remove Slot", new Color(229, 57, 53));
        removeSlotButton.addActionListener(e -> removeEggMoveSlot());

        JToggleButton copyPasteButton = EditorUtils.createStyledToggleButton("Copy/Paste Mode", new Color(255, 152, 0));
        copyPasteButton.addActionListener(e -> toggleCopyPasteMode(copyPasteButton.isSelected()));

        JButton findButton = EditorUtils.createStyledButton("Find", new Color(0, 150, 136));
        findButton.addActionListener(e -> showFindDialog());

        toolbar.add(saveButton);
        toolbar.add(reloadButton);
        toolbar.add(exportButton);
        toolbar.add(importButton);
        toolbar.add(Box.createHorizontalStrut(5));
        toolbar.add(addSlotButton);
        toolbar.add(removeSlotButton);
        toolbar.add(Box.createHorizontalStrut(5));
        toolbar.add(copyPasteButton);
        toolbar.add(findButton);
        toolbar.add(Box.createHorizontalStrut(10));

        JLabel infoLabel = new JLabel("Egg Moves - Edit inherited moves per species");
        infoLabel.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        infoLabel.setForeground(EditorTheme.mutedText());
        toolbar.add(infoLabel);

        return toolbar;
    }

    private JPanel createFrozenColumnTable() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(EditorTheme.surface());

        tableModel = new EggMovesTableModel();
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
            public String getColumnName(int columnIndex) {
                return tableModel.getColumnName(columnIndex);
            }

            @Override
            public Class<?> getColumnClass(int columnIndex) {
                return tableModel.getColumnClass(columnIndex);
            }

            @Override
            public boolean isCellEditable(int rowIndex, int columnIndex) {
                return false;
            }

            @Override
            public void setValueAt(Object aValue, int rowIndex, int columnIndex) {
                tableModel.setValueAt(aValue, rowIndex, columnIndex);
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
                    if (mainTable != null) {
                        EditorUtils.runWithFrozenSyncSuppressed(mainTable, () -> {
                            if (mainTable.getColumnCount() > 0) {
                                mainTable.setColumnSelectionInterval(0, mainTable.getColumnCount() - 1);
                            } else {
                                mainTable.getColumnModel().getSelectionModel().clearSelection();
                            }
                        });
                    }
                }
            }
        };

        styleTable(frozenTable, true);
        TableLayoutDefaults.configureFrozenColumns(frozenTable.getColumnModel(), hasIcons);
        TableLayoutDefaults.applyRowHeight(frozenTable, hasIcons);
        EggMoveCellRenderer frozenRenderer = new EggMoveCellRenderer(true);
        frozenTable.setDefaultRenderer(Object.class, frozenRenderer);
        frozenTable.setDefaultRenderer(String.class, frozenRenderer);

        java.awt.event.MouseAdapter frozenRowSelector = new java.awt.event.MouseAdapter() {
            private int lastClickedRow = -1;
            private boolean draggingFullRow = false;

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
                        if (mainTable != null) {
                            EditorUtils.runWithFrozenSyncSuppressed(mainTable, () -> {
                                mainTable.clearSelection();
                                mainTable.getColumnModel().getSelectionModel().clearSelection();
                            });
                        }
                        lastClickedRow = -1;
                    } else {
                        EditorUtils.setFullRowSelectionActive(frozenTable, true);
                        frozenTable.setRowSelectionInterval(row, row);
                        if (frozenTable.getColumnCount() > 0) {
                            frozenTable.setColumnSelectionInterval(0, frozenTable.getColumnCount() - 1);
                        }
                        if (mainTable != null) {
                            EditorUtils.runWithFrozenSyncSuppressed(mainTable, () -> {
                                mainTable.setRowSelectionInterval(row, row);
                                if (mainTable.getColumnCount() > 0) {
                                    mainTable.setColumnSelectionInterval(0, mainTable.getColumnCount() - 1);
                                } else {
                                    mainTable.getColumnModel().getSelectionModel().clearSelection();
                                }
                            });
                        }
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
                if (row < 0 || row == frozenTable.getSelectedRow()) {
                    return;
                }
                EditorUtils.setFullRowSelectionActive(frozenTable, true);
                frozenTable.setRowSelectionInterval(row, row);
                if (frozenTable.getColumnCount() > 0) {
                    frozenTable.setColumnSelectionInterval(0, frozenTable.getColumnCount() - 1);
                }
                if (mainTable != null) {
                    EditorUtils.runWithFrozenSyncSuppressed(mainTable, () -> {
                        mainTable.setRowSelectionInterval(row, row);
                        if (mainTable.getColumnCount() > 0) {
                            mainTable.setColumnSelectionInterval(0, mainTable.getColumnCount() - 1);
                        } else {
                            mainTable.getColumnModel().getSelectionModel().clearSelection();
                        }
                    });
                }
                lastClickedRow = row;
            }

            @Override
            public void mouseReleased(java.awt.event.MouseEvent e) {
                draggingFullRow = false;
            }
        };
        frozenTable.addMouseListener(frozenRowSelector);
        frozenTable.addMouseMotionListener(frozenRowSelector);

        TableModel mainModel = new AbstractTableModel() {
            @Override
            public int getRowCount() {
                return tableModel.getRowCount();
            }

            @Override
            public int getColumnCount() {
                return Math.max(tableModel.getColumnCount() - 2, 0);
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
            public String getColumnName(int columnIndex) {
                return tableModel.getColumnName(columnIndex + 2);
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
        TableLayoutDefaults.applyRowHeight(mainTable, hasIcons);
        mainTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);

        EggMoveCellRenderer mainRenderer = new EggMoveCellRenderer(false);
        mainTable.setDefaultRenderer(Object.class, mainRenderer);
        mainTable.setDefaultRenderer(String.class, mainRenderer);
        mainTable.setDefaultEditor(String.class, new MoveComboBoxEditor());
        TableLayoutDefaults.refreshHeaderPreferredWidth(mainTable);

        TableColumnModel mainColumnModel = mainTable.getColumnModel();
        for (int col = 0; col < mainColumnModel.getColumnCount(); col++) {
            TableColumn column = mainColumnModel.getColumn(col);
            column.setPreferredWidth(200);
        }

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
    }

    public void save() {
        stopEditing();
        List<String> changes = collectEggMoveChangesForLog();
        if (!changes.isEmpty()) {
            ManualEditRegistry.getInstance().addEntries("Egg Moves", changes);
        }
        romHandler.setEggMoves(createNormalizedCopy(eggMoves));
        if (!EditorUtils.suppressSaveDialogs) {
            JOptionPane.showMessageDialog(this,
                    "- Egg moves updated successfully!\n\nChanges will be saved when you save/randomize the ROM.",
                    "Save Complete",
                    JOptionPane.INFORMATION_MESSAGE);
        }
        commitChanges();
    }

    private void reload() {
        int result = JOptionPane.showConfirmDialog(this,
                "Reload egg moves from ROM?\nAny unsaved changes will be lost.",
                "Confirm Reload",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE);
        if (result == JOptionPane.YES_OPTION) {
            stopEditing();
            restoreFromBackup();
        }
    }

    private void exportToCSV() {
        stopEditing();
        EditorUtils.exportTableToCSV(this, tableModel, "Egg Moves");
    }

    private void importFromCSV() {
        stopEditing();
        EditorUtils.CsvData csvData = EditorUtils.chooseCsvFile(this, "Egg Moves");
        if (csvData == null) {
            return;
        }

        try {
            int applied = EditorUtils.applyCsvDataToTable(csvData.getRows(), tableModel, true);
            tableModel.fireTableDataChanged();
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

    private void addEggMoveSlot() {
        if (copyPasteModeEnabled) {
            JOptionPane.showMessageDialog(this,
                    "Copy/Paste Mode is enabled.\nDisable it to make changes.",
                    "Read-Only Mode",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        int row = mainTable.getSelectedRow();
        if (row < 0) {
            JOptionPane.showMessageDialog(this,
                    "Select a Pokemon first.",
                    "No Selection",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        if (isFormeRow(row)) {
            // Gen5 egg moves can only be saved for base species; refuse forme edits
            // rather than silently dropping them at save.
            JOptionPane.showMessageDialog(this,
                    "Egg moves cannot be edited for alternate formes in this game.\nEdit the base species instead.",
                    "Read-Only Row",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        Species species = pokemonList.get(row + 1);
        List<Integer> moves = eggMoves.computeIfAbsent(species.getNumber(), id -> new ArrayList<>());
        if (moves.size() >= MAX_EGG_MOVES) {
            JOptionPane.showMessageDialog(this,
                    "Maximum number of egg move slots reached.",
                    "Limit Reached",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        touchedKeys.add(species.getNumber());
        moves.add(-1);
        tableModel.fireTableRowsUpdated(row, row);
    }

    private void removeEggMoveSlot() {
        if (copyPasteModeEnabled) {
            JOptionPane.showMessageDialog(this,
                    "Copy/Paste Mode is enabled.\nDisable it to make changes.",
                    "Read-Only Mode",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        int row = mainTable.getSelectedRow();
        if (row < 0) {
            JOptionPane.showMessageDialog(this,
                    "Select a Pokemon first.",
                    "No Selection",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        if (isFormeRow(row)) {
            // Gen5 egg moves can only be saved for base species; refuse forme edits
            // rather than silently dropping them at save.
            JOptionPane.showMessageDialog(this,
                    "Egg moves cannot be edited for alternate formes in this game.\nEdit the base species instead.",
                    "Read-Only Row",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        Species species = pokemonList.get(row + 1);
        List<Integer> moves = eggMoves.get(species.getNumber());
        if (moves == null || moves.isEmpty()) {
            Toolkit.getDefaultToolkit().beep();
            return;
        }

        touchedKeys.add(species.getNumber());
        int lastReal = moves.size() - 1;
        while (lastReal >= 0 && (moves.get(lastReal) == null || moves.get(lastReal) < 0)) {
            lastReal--;
        }
        if (lastReal >= 0) {
            moves.remove(lastReal);
        } else {
            moves.clear();
        }
        tableModel.fireTableRowsUpdated(row, row);
    }

    private void toggleCopyPasteMode(boolean enabled) {
        copyPasteModeEnabled = enabled;
        stopEditing();
        if (enabled) {
            JOptionPane.showMessageDialog(this,
                    "Copy/Paste Mode ON\n\n" +
                            "- Tables are now read-only\n" +
                            "- Select cells and press Ctrl+C to copy data\n" +
                            "- Toggle off to resume editing",
                    "Copy/Paste Mode",
                    JOptionPane.INFORMATION_MESSAGE);
        }
    }

    private void showFindDialog() {
        stopEditing();
        EditorUtils.FindOptions options = EditorUtils.showFindDialog(this, findState.getLastOptions());
        if (options == null) {
            return;
        }
        EditorUtils.performFind(this, frozenTable, mainTable, tableModel, 2, findState, options);
    }

    public void onWindowClosing() {
        stopEditing();
        restoreFromBackup();
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
        eggMovesBackup.clear();
        for (Map.Entry<Integer, List<Integer>> entry : eggMoves.entrySet()) {
            eggMovesBackup.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        // The freshly snapshotted state is the new baseline; nothing is "dirty" yet.
        touchedKeys.clear();
    }

    private void commitChanges() {
        createBackup();
    }

    private void restoreFromBackup() {
        // The eggMoves map is shared across panels (Card View, etc.) via EditorDataCache.
        // Only revert the species this panel itself edited, so a Reload here does not wipe
        // edits other panels made to the same shared map after this panel's backup snapshot.
        for (Integer key : touchedKeys) {
            List<Integer> original = eggMovesBackup.get(key);
            if (original == null) {
                eggMoves.remove(key);
            } else {
                eggMoves.put(key, new ArrayList<>(original));
            }
        }
        touchedKeys.clear();
        tableModel.fireTableDataChanged();
        frozenTable.repaint();
        mainTable.repaint();
    }

    private Map<Integer, List<Integer>> copyEggMoves(Map<Integer, List<Integer>> source) {
        Map<Integer, List<Integer>> copy = new HashMap<>();
        if (source != null) {
            for (Map.Entry<Integer, List<Integer>> entry : source.entrySet()) {
                copy.put(entry.getKey(), new ArrayList<>(entry.getValue()));
            }
        }
        return copy;
    }

    private Map<Integer, List<Integer>> createNormalizedCopy(Map<Integer, List<Integer>> source) {
        Map<Integer, List<Integer>> normalized = new HashMap<>();
        for (Map.Entry<Integer, List<Integer>> entry : source.entrySet()) {
            List<Integer> trimmed = trimTrailingBlanks(entry.getValue());
            if (!trimmed.isEmpty()) {
                normalized.put(entry.getKey(), trimmed);
            }
        }
        return normalized;
    }

    private List<Integer> trimTrailingBlanks(List<Integer> moves) {
        // Drop ALL null/negative entries (not just trailing ones) so a cleared
        // interior slot is never saved as -1 / written as 0xFFFF.
        List<Integer> copy = new ArrayList<>();
        for (Integer m : moves) {
            if (m != null && m >= 0) {
                copy.add(m);
            }
        }
        return copy;
    }

    private void buildMoveLookup() {
        for (int i = 0; i < moveList.size(); i++) {
            Move move = moveList.get(i);
            if (move == null || move.name == null) {
                continue;
            }
            moveLookup.put(normalize(move.name), i);
        }
    }

    private void showInvalidMoveMessage(String value) {
        JOptionPane.showMessageDialog(this,
                "Could not resolve move \"" + value + "\".\nEnter a valid move name or ID.",
                "Unknown Move",
                JOptionPane.WARNING_MESSAGE);
    }

    private List<String> collectEggMoveChangesForLog() {
        Map<Integer, List<Integer>> normalizedCurrent = createNormalizedCopy(eggMoves);
        List<String> changes = new ArrayList<>();
        Set<Integer> processedNumbers = new HashSet<>();
        for (Species species : pokemonList) {
            if (species == null) {
                continue;
            }
            if (!processedNumbers.add(species.getNumber())) {
                continue;
            }
            int id = species.getNumber();
            List<Integer> before = eggMovesBackup.getOrDefault(id, new ArrayList<>());
            List<Integer> after = normalizedCurrent.getOrDefault(id, new ArrayList<>());
            if (!Objects.equals(before, after)) {
                changes.add(String.format("%s: %s -> %s",
                        EditorUtils.speciesNameWithSuffix(species),
                        formatMoveList(before),
                        formatMoveList(after)));
            }
        }
        return changes;
    }

    private String formatMoveList(List<Integer> moves) {
        if (moves == null || moves.isEmpty()) {
            return "(none)";
        }
        return moves.stream()
                .map(this::moveName)
                .collect(Collectors.joining(", "));
    }

    private Species speciesForRow(int rowIndex) {
        if (rowIndex < 0 || rowIndex >= tableModel.getRowCount()) {
            return null;
        }
        return pokemonList.get(rowIndex + 1);
    }

    /**
     * Returns true if the given table row is an alternate forme. Gen5 egg-move
     * persistence only handles base species, so forme rows must be treated as
     * read-only to avoid silently discarding edits at save.
     */
    private boolean isFormeRow(int rowIndex) {
        Species species = speciesForRow(rowIndex);
        return species != null && !species.isBaseForme();
    }

    private Integer resolveMoveId(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        if (text.isEmpty()) {
            return null;
        }

        String normalized = normalize(text);
        if (moveLookup.containsKey(normalized)) {
            return moveLookup.get(normalized);
        }

        if (normalized.startsWith("move #")) {
            normalized = normalized.substring(6).trim();
        }
        if (normalized.startsWith("#")) {
            normalized = normalized.substring(1).trim();
        }

        try {
            int moveId = Integer.parseInt(normalized);
            if (moveId >= 0 && moveId < moveList.size()) {
                return moveId;
            }
        } catch (NumberFormatException ignored) {
        }
        return null;
    }

    private String moveName(int moveId) {
        if (moveId < 0) {
            return "";
        }
        if (moveId >= 0 && moveId < moveList.size()) {
            Move move = moveList.get(moveId);
            if (move != null && move.name != null && !move.name.trim().isEmpty()) {
                return move.name;
            }
        }
        return "Move #" + moveId;
    }

    private static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private class EggMovesTableModel extends AbstractTableModel {
        private final String[] columnNames;

        EggMovesTableModel() {
            columnNames = new String[2 + MAX_EGG_MOVES];
            columnNames[0] = "ID";
            columnNames[1] = "Name";
            for (int i = 0; i < MAX_EGG_MOVES; i++) {
                columnNames[2 + i] = String.format("Egg %02d", i + 1);
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
            return String.class;
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            // Gen5 setEggMoves only persists base-species entries, so edits made on
            // an alternate-forme row would be silently discarded at save. Keep such
            // rows read-only instead of accepting edits that can't be saved.
            return columnIndex > 1 && !copyPasteModeEnabled && !isFormeRow(rowIndex);
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            Species species = pokemonList.get(rowIndex + 1);
            if (species == null) {
                return "";
            }
            if (columnIndex == 0) {
                return String.valueOf(species.getNumber());
            }
            if (columnIndex == 1) {
                return EditorUtils.speciesNameWithSuffix(species);
            }

            List<Integer> moves = eggMoves.get(species.getNumber());
            int slot = columnIndex - 2;
            if (moves == null || slot >= moves.size()) {
                return "";
            }
            // Read into an Integer and null-check: the shared move list can hold null
            // placeholder slots (see trimTrailingPlaceholders / removeEggMoveSlot), so a
            // bare auto-unbox here would throw an NPE during table rendering.
            Integer moveId = moves.get(slot);
            return moveId == null ? "" : moveName(moveId);
        }

        @Override
        public void setValueAt(Object aValue, int rowIndex, int columnIndex) {
            if (!isCellEditable(rowIndex, columnIndex)) {
                return;
            }
            Species species = pokemonList.get(rowIndex + 1);
            if (species == null) {
                return;
            }
            int slot = columnIndex - 2;
            List<Integer> moves = eggMoves.computeIfAbsent(species.getNumber(), id -> new ArrayList<>());
            touchedKeys.add(species.getNumber());
            ensureCapacity(moves, slot + 1);

            String value = aValue == null ? "" : aValue.toString().trim();
            if (value.isEmpty()) {
                moves.set(slot, -1);
                trimTrailingPlaceholders(moves);
                fireTableCellUpdated(rowIndex, columnIndex);
                return;
            }

            Integer moveId = resolveMoveId(value);
            if (moveId == null) {
                Toolkit.getDefaultToolkit().beep();
                showInvalidMoveMessage(value);
                return;
            }
            moves.set(slot, moveId);
            trimTrailingPlaceholders(moves);
            fireTableCellUpdated(rowIndex, columnIndex);
        }

        private void ensureCapacity(List<Integer> moves, int desiredSize) {
            while (moves.size() < desiredSize) {
                moves.add(-1);
            }
        }

        private void trimTrailingPlaceholders(List<Integer> moves) {
            int lastReal = moves.size() - 1;
            while (lastReal >= 0 && (moves.get(lastReal) == null || moves.get(lastReal) < 0)) {
                moves.remove(lastReal);
                lastReal--;
            }
        }
    }

    private class EggMoveCellRenderer extends TableLayoutDefaults.StripedCellRenderer {
        private final boolean frozen;

        EggMoveCellRenderer(boolean frozen) {
            super(frozen, frozen ? new int[] { 0, 1 } : new int[] { 0 });
            this.frozen = frozen;
            if (frozen) {
                setHorizontalAlignment(SwingConstants.LEFT);
                setIconTextGap(10);
            }
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                boolean hasFocus, int row, int column) {
            Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            if (frozen) {
                setHorizontalAlignment(column == 0 ? SwingConstants.CENTER : SwingConstants.LEFT);
            } else {
                setHorizontalAlignment(SwingConstants.CENTER);
            }
            if (frozen && column == 1) {
                if (iconCache.hasIcons()) {
                    setIcon(iconCache.getIcon(speciesForRow(row)));
                } else {
                    setIcon(null);
                }
            } else {
                setIcon(null);
            }
            setText(value == null ? "" : value.toString());
            return c;
        }
    }

    @SuppressWarnings("unchecked")
    private class MoveComboBoxEditor extends DefaultCellEditor {
        MoveComboBoxEditor() {
            super(new JComboBox<>());
            JComboBox<String> combo = (JComboBox<String>) getComponent();
            combo.setEditable(true);
            combo.addItem("");
            for (Move move : moveList) {
                if (move != null && move.name != null && !move.name.trim().isEmpty()) {
                    combo.addItem(move.name);
                }
            }
            EditorUtils.installSearchableComboBox(combo);
        }

        @Override
        public Object getCellEditorValue() {
            Object value = super.getCellEditorValue();
            return value == null ? "" : value.toString();
        }
    }
}
