package com.dabomstew.pkrandom.pokemon.editors;

import com.dabomstew.pkrandom.log.ManualEditRegistry;
import com.dabomstew.pkromio.gamedata.*;
import com.dabomstew.pkromio.romhandlers.RomHandler;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Polished Personal Sheet editor matching PokEditor's clean UI design
 */
public class PersonalSheetPanel extends JPanel {

    private final RomHandler romHandler;
    private final List<Species> pokemonList;
    private final List<Item> itemList;
    private final PokemonIconCache iconCache;
    private JTable frozenTable;
    private JTable mainTable;
    private PokemonDataTableModel tableModel;
    private JScrollPane mainScrollPane;

    // Backup storage for Species data - only write back to Species objects on Save
    private Map<Species, SpeciesBackup> backupData;

    private static final String[] EGG_GROUP_NAMES_LOG = {
            "None", "Monster", "Water 1", "Bug", "Flying", "Field", "Fairy",
            "Grass", "Human-Like", "Water 3", "Mineral", "Amorphous",
            "Water 2", "Ditto", "Dragon", "Undiscovered"
    };

    public PersonalSheetPanel(RomHandler romHandler) {
        this.romHandler = romHandler;
        this.pokemonList = romHandler.getSpecies();
        this.itemList = romHandler.getItems();
        this.iconCache = PokemonIconCache.get(romHandler);
        this.backupData = new HashMap<>();
        // Create backup of all Pokemon data
        createBackup();
        initializeUI();
    }

    private void createBackup() {
        backupData.clear();
        for (Species p : pokemonList) {
            if (p != null) {
                backupData.put(p, new SpeciesBackup(p));
            }
        }
    }

    // Restore all Species from backup (called when window closes without Save)
    private void restoreFromBackup() {
        for (Species p : pokemonList) {
            if (p != null && backupData.containsKey(p)) {
                backupData.get(p).restoreTo(p);
            }
        }
    }

    // Save changes from backup to Species objects (called when Save is clicked)
    private void commitChanges() {
        // Changes are already in the Species objects since we edit them directly
        // But we need to update the backup so future reloads work correctly
        createBackup();
    }

    private void initializeUI() {
        setLayout(new BorderLayout());
        setBackground(EditorTheme.surface());

        // Create toolbar with modern styling
        add(createStyledToolbar(), BorderLayout.NORTH);

        // Create split table with frozen columns
        JPanel tablePanel = createFrozenColumnTable();
        add(tablePanel, BorderLayout.CENTER);
    }

    private JPanel createStyledToolbar() {
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 8));
        toolbar.setBackground(EditorTheme.toolbar());
        toolbar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, EditorTheme.border()),
                new EmptyBorder(5, 5, 5, 5)));

        // Styled buttons
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

        JLabel infoLabel = new JLabel("Edit Pokemon data directly in the table");
        infoLabel.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        infoLabel.setForeground(EditorTheme.mutedText());
        toolbar.add(infoLabel);

        return toolbar;
    }

    private JPanel createFrozenColumnTable() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(EditorTheme.surface());

        // Create table model
        tableModel = new PokemonDataTableModel(pokemonList, romHandler, itemList);

        // Create frozen table (ID and Name columns)
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
        frozenTable.putClientProperty("terminateEditOnFocusLost", Boolean.FALSE);
        styleTable(frozenTable, true);
        TableLayoutDefaults.applyRowHeight(frozenTable, iconCache.hasIcons());
        TableLayoutDefaults.configureFrozenColumns(frozenTable.getColumnModel(), iconCache.hasIcons());

        if (iconCache.hasIcons()) {
            frozenTable.getColumnModel().getColumn(1).setCellRenderer(new SpeciesNameCellRenderer());
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

        // Create main scrollable table
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
        mainTable.putClientProperty("terminateEditOnFocusLost", Boolean.FALSE);
        styleTable(mainTable, false);
        TableLayoutDefaults.applyRowHeight(mainTable, iconCache.hasIcons());
        setupMainTableColumns();
        TableLayoutDefaults.refreshHeaderPreferredWidth(mainTable);

        // Sync row selection model between tables
        frozenTable.setSelectionModel(mainTable.getSelectionModel());
        frozenTable.getColumnModel().getSelectionModel().setSelectionMode(ListSelectionModel.SINGLE_INTERVAL_SELECTION);
        mainTable.getColumnModel().getSelectionModel().setSelectionMode(ListSelectionModel.SINGLE_INTERVAL_SELECTION);
        EditorUtils.installFrozenColumnSync(frozenTable, mainTable);

        // Create scroll panes
        JScrollPane frozenScrollPane = new JScrollPane(frozenTable);
        frozenScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_NEVER);
        frozenScrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        frozenScrollPane.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, EditorTheme.border()));
        frozenScrollPane.setColumnHeaderView(frozenTable.getTableHeader());
        frozenScrollPane.getViewport().setBackground(EditorTheme.surface());

        mainScrollPane = new JScrollPane(mainTable);
        mainScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        mainScrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        mainScrollPane.setColumnHeaderView(mainTable.getTableHeader());
        mainScrollPane.setBorder(BorderFactory.createEmptyBorder());
        mainScrollPane.getViewport().setBackground(EditorTheme.surface());
        EditorUtils.installHeaderViewportSync(mainScrollPane);

        // Sync scrolling
        EditorUtils.linkVerticalScrollBars(frozenScrollPane, mainScrollPane);

        // Layout
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

    private void styleTable(JTable table, boolean isFrozen) {
        int[] leftAligned = isFrozen ? new int[] { 1 } : new int[0];
        TableLayoutDefaults.applySheetTableStyle(table, isFrozen, leftAligned);
    }

    private void setupMainTableColumns() {
        // Column widths matching PokEditor EXACTLY (from PersonalTable.java line 14)
        // {40, 100, 65, 65, 65, 65, 65, 65, 100, 100, 65, 65, 65, 65, 65, 65, 65, 65,
        // 140, 140, 65, 65, 70, 120, 120, 120, 140, 140, 65, 65, 65}
        // Skip first 2 (ID, Name - frozen columns), so start from index 2
        int[] widths = {
                75, 75, 75, 75, 75, 75, // HP, ATK, DEF, SPEED, SP_ATK, SP_DEF
                120, 120, // Type 1, Type 2
                80, 80, // Catch Rate, Exp Drop
                80, 80, 80, 80, 80, 80, // HP/ATK/DEF/SPEED/SP_ATK/SP_DEF EV Yield
                160, 160, // Uncommon Held Item, Rare Held Item
                80, // Gender Ratio
                100, // Hatch Multiplier
                100, // Base Happiness
                140, // Growth Rate
                140, 140, // Egg Group 1, Egg Group 2
                180, 180, // Ability 1, Ability 2
                80, // Run Chance
                80, // Color
                65 // Flip
        };

        for (int i = 0; i < widths.length && i < mainTable.getColumnCount(); i++) {
            TableColumn column = mainTable.getColumnModel().getColumn(i);
            column.setPreferredWidth(widths[i]);

            // Set custom renderers and editors
            int actualCol = i + 2; // Offset by frozen columns
            if (actualCol == 8 || actualCol == 9) {
                // Type 1, Type 2
                column.setCellRenderer(new TypeCellRenderer());
                column.setCellEditor(new TypeComboBoxEditor(romHandler));
            } else if (actualCol == 18 || actualCol == 19) {
                // Uncommon/Rare Held Items
                column.setCellEditor(new ItemComboBoxEditor(itemList));
            } else if (actualCol == 23) {
                // Growth Rate
                column.setCellEditor(new GrowthRateComboBoxEditor());
            } else if (actualCol == 24 || actualCol == 25) {
                // Egg Group 1, Egg Group 2
                column.setCellEditor(new EggGroupComboBoxEditor());
            } else if (actualCol == 26 || actualCol == 27) {
                // Ability 1, Ability 2
                column.setCellEditor(new AbilityComboBoxEditor(romHandler));
            } else if (actualCol == 30) {
                // Flip (checkbox) - now at column 30
                column.setCellEditor(new DefaultCellEditor(new JCheckBox()));
                column.setCellRenderer(new CheckBoxRenderer());
            }
        }
    }

    public void save() {
        stopEditing();

        ManualEditRegistry.getInstance().addEntries("Personal Sheet", collectChangesForLog());

        // Commit changes by updating the backup
        commitChanges();

        if (!EditorUtils.suppressSaveDialogs) {
            JOptionPane.showMessageDialog(this,
                    "- Pokemon data updated successfully!\n\nChanges are stored in memory and will be saved when you save/randomize the ROM.",
                    "Save Complete",
                    JOptionPane.INFORMATION_MESSAGE);
        }
    }

    private void reload() {
        int result = JOptionPane.showConfirmDialog(this,
                "Reload Pokemon data from ROM?\nAny unsaved changes will be lost.",
                "Confirm Reload",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE);

        if (result == JOptionPane.YES_OPTION) {
            stopEditing();
            restoreFromBackup();
            tableModel.fireTableDataChanged();
            frozenTable.repaint();
            mainTable.repaint();
        }
    }

    // Called when the editor window is being closed - restore unsaved changes
    public void onWindowClosing() {
        stopEditing();
        restoreFromBackup();
        tableModel.fireTableDataChanged();
        if (frozenTable != null) {
            frozenTable.repaint();
        }
        if (mainTable != null) {
            mainTable.repaint();
        }
    }

    private void exportToCSV() {
        stopEditing();
        EditorUtils.exportTableToCSV(this, tableModel, "Personal Sheet");
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

        EditorUtils.CsvData csvData = EditorUtils.chooseCsvFile(this, "Personal Sheet");
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

    private boolean copyPasteModeEnabled = false;
    private final EditorUtils.FindState findState = new EditorUtils.FindState();

    private void toggleCopyPasteMode(boolean enabled) {
        copyPasteModeEnabled = enabled;
        // When enabled, table becomes read-only for easy copying
        // Users can still edit when mode is off
        if (enabled) {
            JOptionPane.showMessageDialog(this,
                    "Copy/Paste Mode ON\n\n" +
                            "- Tables are now read-only\n" +
                            "- Select cells and use Ctrl+C to copy\n" +
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

    /**
     * Alternating row colors renderer
     */
    private static class AlternatingRowRenderer extends TableLayoutDefaults.StripedCellRenderer {
        public AlternatingRowRenderer(boolean isFrozen) {
            super(isFrozen, isFrozen ? new int[] { 1 } : new int[0]);
        }
    }

    private class SpeciesNameCellRenderer extends AlternatingRowRenderer {
        public SpeciesNameCellRenderer() {
            super(true);
            // Add padding to the cell
            setBorder(new EmptyBorder(0, 5, 0, 5));
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                boolean isSelected, boolean hasFocus, int row, int column) {
            Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            if (iconCache.hasIcons()) {
                Species species = resolveSpecies(table, row);
                ImageIcon icon = iconCache.getIcon(species);
                if (icon != null) {
                    // Scale icon to fit the new row height, preserving aspect ratio
                    int iconSize = table.getRowHeight() - 8;
                    Image scaledImg = icon.getImage().getScaledInstance(iconSize, iconSize, Image.SCALE_SMOOTH);
                    setIcon(new ImageIcon(scaledImg));
                }
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

    /**
     * Type cell renderer with colored backgrounds
     */
    private static class TypeCellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                boolean isSelected, boolean hasFocus, int row, int column) {
            Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            setHorizontalAlignment(SwingConstants.CENTER);
            setFont(new Font("Segoe UI", Font.BOLD, 12));

            if (!isSelected && value != null && !value.toString().isEmpty()) {
                try {
                    Type type = Type.valueOf(value.toString());
                    c.setBackground(getTypeColor(type));
                    c.setForeground(Color.WHITE);
                } catch (Exception e) {
                    c.setBackground(TableLayoutDefaults.evenRowColor());
                    c.setForeground(EditorTheme.text());
                }
            } else if (!isSelected) {
                c.setBackground(row % 2 == 0 ? TableLayoutDefaults.evenRowColor() : TableLayoutDefaults.oddRowColor());
                c.setForeground(EditorTheme.text());
            }

            setBorder(noFocusBorder);
            return c;
        }

        private Color getTypeColor(Type type) {
            switch (type) {
                case NORMAL:
                    return new Color(168, 168, 120);
                case FIGHTING:
                    return new Color(192, 48, 40);
                case FLYING:
                    return new Color(168, 144, 240);
                case POISON:
                    return new Color(160, 64, 160);
                case GROUND:
                    return new Color(224, 192, 104);
                case ROCK:
                    return new Color(184, 160, 56);
                case BUG:
                    return new Color(168, 184, 32);
                case GHOST:
                    return new Color(112, 88, 152);
                case STEEL:
                    return new Color(184, 184, 208);
                case FIRE:
                    return new Color(240, 128, 48);
                case WATER:
                    return new Color(104, 144, 240);
                case GRASS:
                    return new Color(120, 200, 80);
                case ELECTRIC:
                    return new Color(248, 208, 48);
                case PSYCHIC:
                    return new Color(248, 88, 136);
                case ICE:
                    return new Color(152, 216, 216);
                case DRAGON:
                    return new Color(112, 56, 248);
                case DARK:
                    return new Color(112, 88, 72);
                case FAIRY:
                    return new Color(238, 153, 238);
                default:
                    return EditorTheme.surface();
            }
        }
    }

    // ComboBox editors (same as before but with better styling)
    private class TypeComboBoxEditor extends DefaultCellEditor {
        public TypeComboBoxEditor(RomHandler romHandler) {
            super(new JComboBox<String>());
            JComboBox<String> comboBox = (JComboBox<String>) getComponent();
            comboBox.setFont(new Font("Segoe UI", Font.PLAIN, 12));
            comboBox.addItem("");
            // Use the ROM's actual type list instead of hardcoded Type.values()
            // This respects Gen 5 vanilla (17 types) vs modded with Fairy (18 types)
            for (Type type : romHandler.getTypeTable().getTypes()) {
                comboBox.addItem(type.name());
            }
            EditorUtils.installSearchableComboBox(comboBox);
        }
    }

    private static class AbilityComboBoxEditor extends DefaultCellEditor {
        public AbilityComboBoxEditor(final RomHandler romHandler) {
            super(new JComboBox<String>());
            JComboBox<String> comboBox = (JComboBox<String>) getComponent();
            comboBox.setFont(new Font("Segoe UI", Font.PLAIN, 12));
            comboBox.addItem("0");
            for (int i = 1; i < 200; i++) {
                try {
                    String abilityName = romHandler.abilityName(i);
                    if (abilityName != null && !abilityName.isEmpty()) {
                        comboBox.addItem(i + ": " + abilityName);
                    }
                } catch (Exception e) {
                    // Skip
                }
            }
            EditorUtils.installSearchableComboBox(comboBox);
        }
    }

    private static class ItemComboBoxEditor extends DefaultCellEditor {
        public ItemComboBoxEditor(List<Item> items) {
            super(new JComboBox<String>());
            JComboBox<String> comboBox = (JComboBox<String>) getComponent();
            comboBox.setFont(new Font("Segoe UI", Font.PLAIN, 12));
            comboBox.addItem("None");
            for (Item item : items) {
                if (item != null) {
                    comboBox.addItem(item.getName());
                }
            }
            EditorUtils.installSearchableComboBox(comboBox);
        }
    }

    private static class GrowthRateComboBoxEditor extends DefaultCellEditor {
        // Matching PokEditor's growth rate keys from PersonalTable.java line 15
        private static final String[] GROWTH_RATES = {
                "Medium Fast", "Erratic", "Fluctuating", "Medium Slow", "Fast", "Slow"
        };

        public GrowthRateComboBoxEditor() {
            super(new JComboBox<String>());
            JComboBox<String> comboBox = (JComboBox<String>) getComponent();
            comboBox.setFont(new Font("Segoe UI", Font.PLAIN, 12));
            for (String rate : GROWTH_RATES) {
                comboBox.addItem(rate);
            }
            EditorUtils.installSearchableComboBox(comboBox);
        }
    }

    private static class EggGroupComboBoxEditor extends DefaultCellEditor {
        // Matching PokEditor's egg group keys from PersonalTable.java line 16
        private static final String[] EGG_GROUPS = {
                "None", "Monster", "Water 1", "Bug", "Flying", "Field", "Fairy",
                "Grass", "Human-Like", "Water 3", "Mineral", "Amorphous",
                "Water 2", "Ditto", "Dragon", "Undiscovered"
        };

        public EggGroupComboBoxEditor() {
            super(new JComboBox<String>());
            JComboBox<String> comboBox = (JComboBox<String>) getComponent();
            comboBox.setFont(new Font("Segoe UI", Font.PLAIN, 12));
            for (String group : EGG_GROUPS) {
                comboBox.addItem(group);
            }
            EditorUtils.installSearchableComboBox(comboBox);
        }
    }

    private static class CheckBoxRenderer extends JCheckBox implements TableCellRenderer {
        public CheckBoxRenderer() {
            setHorizontalAlignment(SwingConstants.CENTER);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                boolean isSelected, boolean hasFocus, int row, int column) {
            setSelected(value != null && (Boolean) value);
            setBackground(isSelected ? table.getSelectionBackground()
                    : (row % 2 == 0 ? TableLayoutDefaults.evenRowColor() : TableLayoutDefaults.oddRowColor()));
            return this;
        }
    }

    /**
     * Complete table model with all Pokemon data fields
     */
    private static class PokemonDataTableModel extends AbstractTableModel {
        private final List<Species> pokemonList;
        private final RomHandler romHandler;
        private final List<Item> itemList;

        // Column names matching PokEditor EXACTLY (31 total columns)
        private final String[] columnNames = {
                "ID", "Name",
                "HP", "ATK", "DEF", "SPEED", "SP_ATK", "SP_DEF",
                "Type 1", "Type 2",
                "Catch Rate", "Exp Drop",
                "HP EV Yield", "ATK EV Yield", "DEF EV Yield", "SPEED EV Yield", "SP_ATK EV Yield", "SP_DEF EV Yield",
                "Uncommon Held Item", "Rare Held Item",
                "Gender Ratio",
                "Hatch Multiplier",
                "Base Happiness",
                "Growth Rate",
                "Egg Group 1", "Egg Group 2",
                "Ability 1", "Ability 2",
                "Run Chance",
                "Color",
                "Flip"
        };

        public PokemonDataTableModel(List<Species> pokemonList, RomHandler romHandler, List<Item> itemList) {
            this.pokemonList = pokemonList;
            this.romHandler = romHandler;
            this.itemList = itemList;
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
            // ID, Name, Types, Held Items, Growth Rate, Egg Groups, Abilities = String
            // Flip = Boolean
            // Everything else = Integer
            if (col <= 1 || col == 8 || col == 9 || col == 18 || col == 19 ||
                    col == 23 || col == 24 || col == 25 || col == 26 || col == 27) {
                return String.class;
            } else if (col == 30) { // Flip moved to column 30
                return Boolean.class;
            }
            return Integer.class;
        }

        @Override
        public boolean isCellEditable(int row, int col) {
            return col > 1; // Everything except ID and Name is editable
        }

        @Override
        public Object getValueAt(int row, int col) {
            Species p = pokemonList.get(row + 1);
            if (p == null)
                return "";

            switch (col) {
                case 0:
                    return p.getNumber(); // ID
                case 1:
                    return p.getName(); // Name
                case 2:
                    return p.getHp(); // HP
                case 3:
                    return p.getAttack(); // ATK
                case 4:
                    return p.getDefense(); // DEF
                case 5:
                    return p.getSpeed(); // SPEED
                case 6:
                    return p.getSpatk(); // SP_ATK
                case 7:
                    return p.getSpdef(); // SP_DEF
                case 8:
                    return p.getPrimaryType(false) != null ? p.getPrimaryType(false).name() : ""; // Type 1
                case 9:
                    return p.getSecondaryType(false) != null ? p.getSecondaryType(false).name() : ""; // Type 2
                case 10:
                    return p.getCatchRate(); // Catch Rate
                case 11:
                    return p.getExpYield(); // Exp Drop
                case 12:
                    return p.getHpEvYield(); // HP EV Yield
                case 13:
                    return p.getAttackEvYield(); // ATK EV Yield
                case 14:
                    return p.getDefenseEvYield(); // DEF EV Yield
                case 15:
                    return p.getSpeedEvYield(); // SPEED EV Yield
                case 16:
                    return p.getSpatkEvYield(); // SP_ATK EV Yield
                case 17:
                    return p.getSpdefEvYield(); // SP_DEF EV Yield
                case 18:
                    // Guaranteed-item species store the real item only in guaranteedHeldItem
                    // (both ROM slots equal), leaving common/rare null. Surface it here so the
                    // item isn't hidden as 'None'.
                    return getItemName(p.getGuaranteedHeldItem() != null
                            ? p.getGuaranteedHeldItem()
                            : p.getCommonHeldItem()); // Uncommon Held Item
                case 19:
                    return getItemName(p.getGuaranteedHeldItem() != null
                            ? p.getGuaranteedHeldItem()
                            : p.getRareHeldItem()); // Rare Held Item
                case 20:
                    return p.getGenderRatio(); // Gender Ratio
                case 21:
                    return p.getCallRate(); // Hatch Multiplier (using CallRate since Species doesn't have
                                            // hatchMultiplier)
                case 22:
                    return p.getBaseHappiness(); // Base Happiness
                case 23:
                    return p.getGrowthCurve() != null ? getGrowthRateName(p.getGrowthCurve()) : "Medium Fast"; // Growth
                                                                                                               // Rate
                case 24:
                    return getEggGroupName(p.getEggGroup1()); // Egg Group 1
                case 25:
                    return getEggGroupName(p.getEggGroup2()); // Egg Group 2
                case 26:
                    return getAbilityName(p.getAbility1()); // Ability 1
                case 27:
                    return getAbilityName(p.getAbility2()); // Ability 2
                case 28:
                    return p.getRunChance(); // Run Chance
                case 29:
                    return p.getColor(); // Color
                case 30:
                    return p.isFlip(); // Flip
                default:
                    return "";
            }
        }

        @Override
        public void setValueAt(Object val, int row, int col) {
            Species p = pokemonList.get(row + 1);
            if (p == null)
                return;

            try {
                switch (col) {
                    case 2:
                        p.setHp(parseBoundedInt(val, 1, 255));
                        break; // HP
                    case 3:
                        p.setAttack(parseBoundedInt(val, 1, 255));
                        break; // ATK
                    case 4:
                        p.setDefense(parseBoundedInt(val, 1, 255));
                        break; // DEF
                    case 5:
                        p.setSpeed(parseBoundedInt(val, 1, 255));
                        break; // SPEED
                    case 6:
                        p.setSpatk(parseBoundedInt(val, 1, 255));
                        break; // SP_ATK
                    case 7:
                        p.setSpdef(parseBoundedInt(val, 1, 255));
                        break; // SP_DEF
                    case 8: // Type 1
                        if (val != null && !val.toString().isEmpty()) {
                            p.setPrimaryType(Type.valueOf(val.toString()));
                        }
                        break;
                    case 9: // Type 2
                        if (val != null && !val.toString().isEmpty()) {
                            p.setSecondaryType(Type.valueOf(val.toString()));
                        } else {
                            p.setSecondaryType(null);
                        }
                        break;
                    case 10:
                        p.setCatchRate(parseBoundedInt(val, 0, 255));
                        break; // Catch Rate
                    case 11:
                        p.setExpYield(parseBoundedInt(val, 0, 255));
                        break; // Exp Drop
                    case 12:
                        p.setHpEvYield(parseBoundedInt(val, 0, 3));
                        break; // HP EV Yield
                    case 13:
                        p.setAttackEvYield(parseBoundedInt(val, 0, 3));
                        break; // ATK EV Yield
                    case 14:
                        p.setDefenseEvYield(parseBoundedInt(val, 0, 3));
                        break; // DEF EV Yield
                    case 15:
                        p.setSpeedEvYield(parseBoundedInt(val, 0, 3));
                        break; // SPEED EV Yield
                    case 16:
                        p.setSpatkEvYield(parseBoundedInt(val, 0, 3));
                        break; // SP_ATK EV Yield
                    case 17:
                        p.setSpdefEvYield(parseBoundedInt(val, 0, 3));
                        break; // SP_DEF EV Yield
                    case 18:
                        // A guaranteed held item overrides both slots on ROM write, so clear it
                        // when the user edits a per-slot value or the edit would be discarded.
                        // Preserve the existing item in the other (rare) slot if it was guaranteed.
                        promoteGuaranteedToSlots(p);
                        p.setCommonHeldItem(findItem(val.toString()));
                        break; // Uncommon Held Item
                    case 19:
                        promoteGuaranteedToSlots(p);
                        p.setRareHeldItem(findItem(val.toString()));
                        break; // Rare Held Item
                    case 20:
                        p.setGenderRatio(parseBoundedInt(val, 0, 255));
                        break; // Gender Ratio
                    case 21:
                        p.setCallRate(parseBoundedInt(val, 0, 255));
                        break; // Hatch Multiplier
                    case 22:
                        p.setBaseHappiness(parseBoundedInt(val, 0, 255));
                        break; // Base Happiness
                    case 23: // Growth Rate
                        if (val != null && !val.toString().isEmpty()) {
                            p.setGrowthCurve(parseGrowthRate(val.toString()));
                        }
                        break;
                    case 24:
                        p.setEggGroup1(parseEggGroup(val.toString()));
                        break; // Egg Group 1
                    case 25:
                        p.setEggGroup2(parseEggGroup(val.toString()));
                        break; // Egg Group 2
                    case 26:
                        p.setAbility1(parseAbility(val.toString()));
                        break; // Ability 1
                    case 27:
                        p.setAbility2(parseAbility(val.toString()));
                        break; // Ability 2
                    case 28:
                        p.setRunChance(parseBoundedInt(val, 0, 255));
                        break; // Run Chance
                    case 29:
                        p.setColor(parseBoundedInt(val, 0, 255));
                        break; // Color
                    case 30:
                        p.setFlip((Boolean) val);
                        break; // Flip
                }
                fireTableCellUpdated(row, col);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        private String getItemName(Item item) {
            return item != null ? item.getName() : "None";
        }

        private String getAbilityName(int id) {
            if (id == 0)
                return "0";
            try {
                String name = romHandler.abilityName(id);
                return id + ": " + (name != null ? name : "Unknown");
            } catch (Exception e) {
                return String.valueOf(id);
            }
        }

        // Matching PokEditor's egg group keys from PersonalTable.java line 16
        private static final String[] EGG_GROUP_NAMES = {
                "None", "Monster", "Water 1", "Bug", "Flying", "Field", "Fairy",
                "Grass", "Human-Like", "Water 3", "Mineral", "Amorphous",
                "Water 2", "Ditto", "Dragon", "Undiscovered"
        };

        private String getEggGroupName(int id) {
            if (id >= 0 && id < EGG_GROUP_NAMES.length) {
                return EGG_GROUP_NAMES[id];
            }
            return "None";
        }

        private int parseEggGroup(String name) {
            if (name == null || name.isEmpty())
                return 0;
            for (int i = 0; i < EGG_GROUP_NAMES.length; i++) {
                if (EGG_GROUP_NAMES[i].equals(name)) {
                    return i;
                }
            }
            return 0;
        }

        // Convert ExpCurve enum to PokEditor's growth rate names
        private String getGrowthRateName(ExpCurve curve) {
            if (curve == null)
                return "Medium Fast";
            switch (curve) {
                case MEDIUM_FAST:
                    return "Medium Fast";
                case ERRATIC:
                    return "Erratic";
                case FLUCTUATING:
                    return "Fluctuating";
                case MEDIUM_SLOW:
                    return "Medium Slow";
                case FAST:
                    return "Fast";
                case SLOW:
                    return "Slow";
                default:
                    return "Medium Fast";
            }
        }

        // Convert PokEditor's growth rate names to ExpCurve enum
        private ExpCurve parseGrowthRate(String name) {
            if (name == null || name.isEmpty())
                return ExpCurve.MEDIUM_FAST;
            switch (name) {
                case "Medium Fast":
                    return ExpCurve.MEDIUM_FAST;
                case "Erratic":
                    return ExpCurve.ERRATIC;
                case "Fluctuating":
                    return ExpCurve.FLUCTUATING;
                case "Medium Slow":
                    return ExpCurve.MEDIUM_SLOW;
                case "Fast":
                    return ExpCurve.FAST;
                case "Slow":
                    return ExpCurve.SLOW;
                default:
                    return ExpCurve.MEDIUM_FAST;
            }
        }

        private int parseBoundedInt(Object val, int min, int max) {
            int parsed = parseInt(val);
            return clampInt(parsed, min, max);
        }

        private int parseInt(Object val) {
            if (val instanceof Integer)
                return (Integer) val;
            try {
                return Integer.parseInt(val.toString());
            } catch (Exception e) {
                return 0;
            }
        }

        private int clampInt(int value, int min, int max) {
            if (value < min) {
                return min;
            }
            if (value > max) {
                return max;
            }
            return value;
        }

        private int parseAbility(String val) {
            if (val == null || val.isEmpty())
                return 0;
            String[] parts = val.split(":");
            try {
                return Integer.parseInt(parts[0].trim());
            } catch (Exception e) {
                return 0;
            }
        }

        private Item findItem(String name) {
            if (name == null || name.equals("None"))
                return null;
            for (Item item : itemList) {
                if (item != null && item.getName().equals(name))
                    return item;
            }
            return null;
        }

        // When a species uses the 'guaranteed' held-item layout the real item lives only in
        // guaranteedHeldItem (common/rare are null) and the ROM writer overwrites BOTH slots
        // from it, discarding any per-slot edit. Before editing a slot, copy the guaranteed
        // item into the common/rare slots and clear guaranteed so per-slot edits take effect.
        private void promoteGuaranteedToSlots(Species p) {
            Item guaranteed = p.getGuaranteedHeldItem();
            if (guaranteed != null) {
                p.setCommonHeldItem(guaranteed);
                p.setRareHeldItem(guaranteed);
                p.setGuaranteedHeldItem(null);
            }
        }
    }

    private List<String> collectChangesForLog() {
        List<String> changes = new ArrayList<>();
        for (Species species : pokemonList) {
            if (species == null) {
                continue;
            }
            SpeciesBackup backup = backupData.get(species);
            if (backup == null) {
                continue;
            }
            List<String> diffs = new ArrayList<>();
            addIntDiff(diffs, "HP", backup.hp, species.getHp());
            addIntDiff(diffs, "ATK", backup.attack, species.getAttack());
            addIntDiff(diffs, "DEF", backup.defense, species.getDefense());
            addIntDiff(diffs, "SPD", backup.speed, species.getSpeed());
            addIntDiff(diffs, "SP.ATK", backup.spatk, species.getSpatk());
            addIntDiff(diffs, "SP.DEF", backup.spdef, species.getSpdef());

            addStringDiff(diffs, "Type 1", formatType(backup.primaryType), formatType(species.getPrimaryType(false)));
            addStringDiff(diffs, "Type 2", formatType(backup.secondaryType),
                    formatType(species.getSecondaryType(false)));

            addIntDiff(diffs, "Catch Rate", backup.catchRate, species.getCatchRate());
            addIntDiff(diffs, "EXP Yield", backup.expYield, species.getExpYield());

            addIntDiff(diffs, "HP EV", backup.hpEvYield, species.getHpEvYield());
            addIntDiff(diffs, "ATK EV", backup.attackEvYield, species.getAttackEvYield());
            addIntDiff(diffs, "DEF EV", backup.defenseEvYield, species.getDefenseEvYield());
            addIntDiff(diffs, "SPD EV", backup.speedEvYield, species.getSpeedEvYield());
            addIntDiff(diffs, "SP.ATK EV", backup.spatkEvYield, species.getSpatkEvYield());
            addIntDiff(diffs, "SP.DEF EV", backup.spdefEvYield, species.getSpdefEvYield());

            addStringDiff(diffs, "Common Item", formatItem(backup.commonHeldItem),
                    formatItem(species.getCommonHeldItem()));
            addStringDiff(diffs, "Rare Item", formatItem(backup.rareHeldItem), formatItem(species.getRareHeldItem()));
            addStringDiff(diffs, "Guaranteed Item", formatItem(backup.guaranteedHeldItem),
                    formatItem(species.getGuaranteedHeldItem()));

            addIntDiff(diffs, "Gender Ratio", backup.genderRatio, species.getGenderRatio());
            addIntDiff(diffs, "Call Rate", backup.callRate, species.getCallRate());
            addIntDiff(diffs, "Base Happiness", backup.baseHappiness, species.getBaseHappiness());
            addStringDiff(diffs, "Growth Curve",
                    formatGrowthCurve(backup.growthCurve), formatGrowthCurve(species.getGrowthCurve()));
            addStringDiff(diffs, "Egg Group 1", eggGroupName(backup.eggGroup1), eggGroupName(species.getEggGroup1()));
            addStringDiff(diffs, "Egg Group 2", eggGroupName(backup.eggGroup2), eggGroupName(species.getEggGroup2()));

            addStringDiff(diffs, "Ability 1", formatAbility(backup.ability1), formatAbility(species.getAbility1()));
            addStringDiff(diffs, "Ability 2", formatAbility(backup.ability2), formatAbility(species.getAbility2()));

            addIntDiff(diffs, "Run Chance", backup.runChance, species.getRunChance());
            addIntDiff(diffs, "Color", backup.color, species.getColor());
            if (backup.flip != species.isFlip()) {
                diffs.add(String.format("Flip (old %s) -> (new %s)", backup.flip, species.isFlip()));
            }

            if (!diffs.isEmpty()) {
                changes.add(String.format("%s: %s", species.getFullName(), String.join(", ", diffs)));
            }
        }
        return changes;
    }

    private void addIntDiff(List<String> diffs, String label, int before, int after) {
        if (before != after) {
            diffs.add(String.format("%s (old %d) -> (new %d)", label, before, after));
        }
    }

    private void addStringDiff(List<String> diffs, String label, String before, String after) {
        if (!Objects.equals(before, after)) {
            diffs.add(String.format("%s (old %s) -> (new %s)", label, before, after));
        }
    }

    private String formatType(Type type) {
        return type != null ? type.name() : "None";
    }

    private String formatItem(Item item) {
        return item != null ? item.getName() : "None";
    }

    private String formatAbility(int abilityId) {
        if (abilityId == 0) {
            return "0";
        }
        try {
            String name = romHandler.abilityName(abilityId);
            return abilityId + ": " + (name != null ? name : "Unknown");
        } catch (Exception e) {
            return String.valueOf(abilityId);
        }
    }

    private String formatGrowthCurve(ExpCurve curve) {
        return curve != null ? curve.name() : "Unknown";
    }

    private String eggGroupName(int id) {
        if (id >= 0 && id < EGG_GROUP_NAMES_LOG.length) {
            return EGG_GROUP_NAMES_LOG[id];
        }
        return String.valueOf(id);
    }

    /**
     * Helper class to backup and restore Species data
     */
    private static class SpeciesBackup {
        // All editable fields from PersonalSheetPanel
        private int hp, attack, defense, speed, spatk, spdef;
        private Type primaryType, secondaryType;
        private int catchRate, expYield;
        private int hpEvYield, attackEvYield, defenseEvYield, speedEvYield, spatkEvYield, spdefEvYield;
        private Item commonHeldItem, rareHeldItem, guaranteedHeldItem;
        private int genderRatio, callRate, baseHappiness;
        private ExpCurve growthCurve;
        private int eggGroup1, eggGroup2;
        private int ability1, ability2;
        private int runChance, color;
        private boolean flip;

        public SpeciesBackup(Species p) {
            // Backup all editable fields
            this.hp = p.getHp();
            this.attack = p.getAttack();
            this.defense = p.getDefense();
            this.speed = p.getSpeed();
            this.spatk = p.getSpatk();
            this.spdef = p.getSpdef();
            this.primaryType = p.getPrimaryType(false);
            this.secondaryType = p.getSecondaryType(false);
            this.catchRate = p.getCatchRate();
            this.expYield = p.getExpYield();
            this.hpEvYield = p.getHpEvYield();
            this.attackEvYield = p.getAttackEvYield();
            this.defenseEvYield = p.getDefenseEvYield();
            this.speedEvYield = p.getSpeedEvYield();
            this.spatkEvYield = p.getSpatkEvYield();
            this.spdefEvYield = p.getSpdefEvYield();
            this.commonHeldItem = p.getCommonHeldItem();
            this.rareHeldItem = p.getRareHeldItem();
            this.guaranteedHeldItem = p.getGuaranteedHeldItem();
            this.genderRatio = p.getGenderRatio();
            this.callRate = p.getCallRate();
            this.baseHappiness = p.getBaseHappiness();
            this.growthCurve = p.getGrowthCurve();
            this.eggGroup1 = p.getEggGroup1();
            this.eggGroup2 = p.getEggGroup2();
            this.ability1 = p.getAbility1();
            this.ability2 = p.getAbility2();
            this.runChance = p.getRunChance();
            this.color = p.getColor();
            this.flip = p.isFlip();
        }

        public void restoreTo(Species p) {
            // Restore all fields back to the Species object
            p.setHp(this.hp);
            p.setAttack(this.attack);
            p.setDefense(this.defense);
            p.setSpeed(this.speed);
            p.setSpatk(this.spatk);
            p.setSpdef(this.spdef);
            p.setPrimaryType(this.primaryType);
            p.setSecondaryType(this.secondaryType);
            p.setCatchRate(this.catchRate);
            p.setExpYield(this.expYield);
            p.setHpEvYield(this.hpEvYield);
            p.setAttackEvYield(this.attackEvYield);
            p.setDefenseEvYield(this.defenseEvYield);
            p.setSpeedEvYield(this.speedEvYield);
            p.setSpatkEvYield(this.spatkEvYield);
            p.setSpdefEvYield(this.spdefEvYield);
            p.setCommonHeldItem(this.commonHeldItem);
            p.setRareHeldItem(this.rareHeldItem);
            p.setGuaranteedHeldItem(this.guaranteedHeldItem);
            p.setGenderRatio(this.genderRatio);
            p.setCallRate(this.callRate);
            p.setBaseHappiness(this.baseHappiness);
            p.setGrowthCurve(this.growthCurve);
            p.setEggGroup1(this.eggGroup1);
            p.setEggGroup2(this.eggGroup2);
            p.setAbility1(this.ability1);
            p.setAbility2(this.ability2);
            p.setRunChance(this.runChance);
            p.setColor(this.color);
            p.setFlip(this.flip);
        }
    }
}
