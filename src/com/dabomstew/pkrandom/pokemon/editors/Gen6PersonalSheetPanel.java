package com.dabomstew.pkrandom.pokemon.editors;

import com.dabomstew.pkrandom.log.ManualEditRegistry;
import com.dabomstew.pkromio.gamedata.*;
import com.dabomstew.pkromio.romhandlers.RomHandler;

import javax.swing.*;
import javax.swing.table.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Gen 6 Personal Sheet editor for X, Y, Omega Ruby, Alpha Sapphire
 * Based on Gen5PersonalSheetPanel structure but adapted for Gen 6 data format.
 *
 * Key differences from Gen 5:
 * - 3 held item slots instead of 4 (no Dark Grass item)
 * - Gen 6 specific fields handled by Gen6RomHandler
 */
public class Gen6PersonalSheetPanel extends JPanel {

    private final RomHandler romHandler;
    private final List<Species> pokemonList;
    private final List<Item> itemList;
    private final PokemonIconCache iconCache;
    private JTable frozenTable;
    private JTable mainTable;
    private PokemonDataTableModel tableModel;
    private JScrollPane mainScrollPane;
    private boolean copyPasteModeEnabled = false;
    private final EditorUtils.FindState findState = new EditorUtils.FindState();

    // Backup storage for Species data
    private Map<Species, SpeciesBackup> backupData;

    private static final String[] EGG_GROUP_NAMES_LOG = {
            "None", "Monster", "Water 1", "Bug", "Flying", "Field", "Fairy",
            "Grass", "Human-Like", "Water 3", "Mineral", "Amorphous",
            "Water 2", "Ditto", "Dragon", "Undiscovered"
    };

    public Gen6PersonalSheetPanel(RomHandler romHandler) {
        this.romHandler = romHandler;
        this.pokemonList = romHandler.getSpeciesInclFormes();
        this.itemList = romHandler.getItems();
        this.iconCache = PokemonIconCache.get(romHandler);
        this.backupData = new HashMap<>();
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

    private void restoreFromBackup() {
        for (Species p : pokemonList) {
            if (p != null && backupData.containsKey(p)) {
                backupData.get(p).restoreTo(p);
            }
        }
    }

    private void commitChanges() {
        createBackup();
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
        toolbar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, EditorTheme.border()));

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

        return toolbar;
    }

    private JPanel createFrozenColumnTable() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(EditorTheme.surface());

        // Create table model
        tableModel = new PokemonDataTableModel(pokemonList, romHandler, itemList);

        // Create frozen table (ID and Name columns only)
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

        // Add ID column row selection behavior
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
        frozenTable.addMouseListener(frozenRowSelector);
        frozenTable.addMouseMotionListener(frozenRowSelector);

        // Create main scrollable table (all columns except ID and Name)
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
        TableColumnModel columnModel = mainTable.getColumnModel();
        for (int i = 0; i < mainTable.getColumnCount(); i++) {
            TableColumn column = columnModel.getColumn(i);
            int modelCol = i + 2; // account for frozen ID/Name columns
            column.setPreferredWidth(getColumnWidth(modelCol));

            if (modelCol == PokemonDataTableModel.COL_TYPE_1 || modelCol == PokemonDataTableModel.COL_TYPE_2) {
                column.setCellRenderer(new TypeCellRenderer());
                column.setCellEditor(new TypeComboBoxEditor(romHandler));
            } else if (modelCol >= PokemonDataTableModel.COL_COMMON_ITEM
                    && modelCol <= PokemonDataTableModel.COL_GUARANTEED_ITEM) {
                column.setCellEditor(new ItemComboBoxEditor(itemList));
            } else if (modelCol == PokemonDataTableModel.COL_GROWTH_RATE) {
                column.setCellEditor(new GrowthRateComboBoxEditor());
            } else if (modelCol == PokemonDataTableModel.COL_EGG_GROUP_1
                    || modelCol == PokemonDataTableModel.COL_EGG_GROUP_2) {
                column.setCellEditor(new EggGroupComboBoxEditor());
            } else if (modelCol == PokemonDataTableModel.COL_ABILITY_1
                    || modelCol == PokemonDataTableModel.COL_ABILITY_2
                    || modelCol == PokemonDataTableModel.COL_ABILITY_3) {
                column.setCellEditor(new AbilityComboBoxEditor(romHandler));
            } else if (modelCol == PokemonDataTableModel.COL_FLIP) {
                column.setCellEditor(new DefaultCellEditor(new JCheckBox()));
                column.setCellRenderer(new CheckBoxRenderer());
            }
        }
    }

    private int getColumnWidth(int modelCol) {
        switch (modelCol) {
            case PokemonDataTableModel.COL_HP:
            case PokemonDataTableModel.COL_ATK:
            case PokemonDataTableModel.COL_DEF:
            case PokemonDataTableModel.COL_SPEED:
            case PokemonDataTableModel.COL_SP_ATK:
            case PokemonDataTableModel.COL_SP_DEF:
            case PokemonDataTableModel.COL_BST:
                return 65;
            case PokemonDataTableModel.COL_TYPE_1:
            case PokemonDataTableModel.COL_TYPE_2:
                return 100;
            case PokemonDataTableModel.COL_CATCH_RATE:
                return 70;
            case PokemonDataTableModel.COL_EXP_YIELD:
                return 80;
            case PokemonDataTableModel.COL_HP_EV:
            case PokemonDataTableModel.COL_ATK_EV:
            case PokemonDataTableModel.COL_DEF_EV:
            case PokemonDataTableModel.COL_SPEED_EV:
            case PokemonDataTableModel.COL_SP_ATK_EV:
            case PokemonDataTableModel.COL_SP_DEF_EV:
                return 65;
            case PokemonDataTableModel.COL_COMMON_ITEM:
            case PokemonDataTableModel.COL_RARE_ITEM:
                return 150;
            case PokemonDataTableModel.COL_GENDER_RATIO:
                return 90;
            case PokemonDataTableModel.COL_HATCH_COUNTER:
                return 90;
            case PokemonDataTableModel.COL_BASE_HAPPINESS:
                return 100;
            case PokemonDataTableModel.COL_GROWTH_RATE:
                return 110;
            case PokemonDataTableModel.COL_EGG_GROUP_1:
            case PokemonDataTableModel.COL_EGG_GROUP_2:
                return 110;
            case PokemonDataTableModel.COL_ABILITY_1:
            case PokemonDataTableModel.COL_ABILITY_2:
            case PokemonDataTableModel.COL_ABILITY_3:
                return 150;
            case PokemonDataTableModel.COL_RUN_CHANCE:
                return 80;
            case PokemonDataTableModel.COL_HEIGHT:
            case PokemonDataTableModel.COL_WEIGHT:
                return 70;
            case PokemonDataTableModel.COL_COLOR:
                return 70;
            case PokemonDataTableModel.COL_FLIP:
                return 60;
            default:
                return 100;
        }
    }

    public void save() {
        stopEditing();

        List<String> changes = collectChangesForLog();
        commitChanges();

        if (!changes.isEmpty()) {
            ManualEditRegistry.getInstance().addEntries("Personal Sheet", changes);
        }

        if (!EditorUtils.suppressSaveDialogs) {
            JOptionPane.showMessageDialog(this,
                    "- Pokemon data updated successfully!\n\nChanges are stored in memory and will be saved when you save/randomize the ROM.",
                    "Save Complete",
                    JOptionPane.INFORMATION_MESSAGE);
        }
    }

    private List<String> collectChangesForLog() {
        List<String> changes = new ArrayList<>();
        for (Species p : pokemonList) {
            if (p == null)
                continue;

            SpeciesBackup backup = backupData.get(p);
            if (backup == null)
                continue;

            List<String> diffs = new ArrayList<>();
            addIntDiff(diffs, "HP", backup.hp, p.getHp());
            addIntDiff(diffs, "ATK", backup.attack, p.getAttack());
            addIntDiff(diffs, "DEF", backup.defense, p.getDefense());
            addIntDiff(diffs, "SPD", backup.speed, p.getSpeed());
            addIntDiff(diffs, "SP.ATK", backup.spatk, p.getSpatk());
            addIntDiff(diffs, "SP.DEF", backup.spdef, p.getSpdef());

            addStringDiff(diffs, "Type 1", formatType(backup.primaryType), formatType(p.getPrimaryType(false)));
            addStringDiff(diffs, "Type 2", formatType(backup.secondaryType), formatType(p.getSecondaryType(false)));

            addIntDiff(diffs, "Catch Rate", backup.catchRate, p.getCatchRate());
            addIntDiff(diffs, "Exp Yield", backup.expYield, p.getExpYield());

            addIntDiff(diffs, "HP EV", backup.hpEvYield, p.getHpEvYield());
            addIntDiff(diffs, "ATK EV", backup.attackEvYield, p.getAttackEvYield());
            addIntDiff(diffs, "DEF EV", backup.defenseEvYield, p.getDefenseEvYield());
            addIntDiff(diffs, "SPD EV", backup.speedEvYield, p.getSpeedEvYield());
            addIntDiff(diffs, "SP.ATK EV", backup.spatkEvYield, p.getSpatkEvYield());
            addIntDiff(diffs, "SP.DEF EV", backup.spdefEvYield, p.getSpdefEvYield());

            // Gen6/Gen7 has no Dark Grass item slot, so it is intentionally omitted here.
            addStringDiff(diffs, "Guaranteed Item", formatItem(backup.guaranteedHeldItem),
                    formatItem(p.getGuaranteedHeldItem()));
            addStringDiff(diffs, "Common Item", formatItem(backup.commonHeldItem), formatItem(p.getCommonHeldItem()));
            addStringDiff(diffs, "Rare Item", formatItem(backup.rareHeldItem), formatItem(p.getRareHeldItem()));

            addIntDiff(diffs, "Gender Ratio", backup.genderRatio, p.getGenderRatio());
            addIntDiff(diffs, "Hatch Counter", backup.hatchCounter, p.getHatchCounter());
            addIntDiff(diffs, "Base Happiness", backup.baseHappiness, p.getBaseHappiness());
            addStringDiff(diffs, "Growth Rate", formatGrowthCurve(backup.growthCurve),
                    formatGrowthCurve(p.getGrowthCurve()));
            addStringDiff(diffs, "Egg Group 1", eggGroupName(backup.eggGroup1), eggGroupName(p.getEggGroup1()));
            addStringDiff(diffs, "Egg Group 2", eggGroupName(backup.eggGroup2), eggGroupName(p.getEggGroup2()));

            addStringDiff(diffs, "Ability 1", formatAbilityLog(backup.ability1), formatAbilityLog(p.getAbility1()));
            addStringDiff(diffs, "Ability 2", formatAbilityLog(backup.ability2), formatAbilityLog(p.getAbility2()));
            addStringDiff(diffs, "Ability 3", formatAbilityLog(backup.ability3), formatAbilityLog(p.getAbility3()));

            addIntDiff(diffs, "Run Chance", backup.runChance, p.getRunChance());
            addIntDiff(diffs, "Height", backup.height, p.getHeight());
            addIntDiff(diffs, "Weight", backup.weight, p.getWeight());
            addIntDiff(diffs, "Color", backup.color, p.getColor());
            if (backup.flip != p.isFlip()) {
                diffs.add(String.format("Flip (old %s) -> (new %s)", backup.flip, p.isFlip()));
            }

            if (!diffs.isEmpty()) {
                changes.add(String.format("Pokemon #%d: %s | %s", p.getNumber(),
                        EditorUtils.speciesNameWithSuffix(p), String.join(", ", diffs)));
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

    private String formatAbilityLog(int abilityId) {
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
        if (curve == null) {
            return "Medium Fast";
        }
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
                return curve.name();
        }
    }

    private String eggGroupName(int id) {
        if (id >= 0 && id < EGG_GROUP_NAMES_LOG.length) {
            return EGG_GROUP_NAMES_LOG[id];
        }
        return String.valueOf(id);
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
     * Species name cell renderer with Pokemon icon
     */
    private class SpeciesNameCellRenderer extends TableLayoutDefaults.StripedCellRenderer {
        public SpeciesNameCellRenderer() {
            super(true, 1);
            setHorizontalAlignment(SwingConstants.LEFT);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                boolean isSelected, boolean hasFocus, int row, int column) {
            Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            Species species = (row >= 0 && row + 1 < pokemonList.size()) ? pokemonList.get(row + 1) : null;
            if (iconCache.hasIcons() && species != null) {
                setIcon(iconCache.getIcon(species));
            } else {
                setIcon(null);
            }
            setText(EditorUtils.formatSpeciesDisplayName(species));
            return c;
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
            setFont(new Font("Segoe UI", Font.BOLD, 11));

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

    // ComboBox editors
    private class TypeComboBoxEditor extends DefaultCellEditor {
        public TypeComboBoxEditor(RomHandler romHandler) {
            super(new JComboBox<String>());
            JComboBox<String> comboBox = (JComboBox<String>) getComponent();
            comboBox.setFont(new Font("Segoe UI", Font.PLAIN, 11));
            comboBox.addItem("");
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
            comboBox.setFont(new Font("Segoe UI", Font.PLAIN, 11));
            comboBox.addItem("0");
            for (int i = 1; i < 250; i++) {
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
            comboBox.setFont(new Font("Segoe UI", Font.PLAIN, 11));
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
        private static final String[] GROWTH_RATES = {
                "Medium Fast", "Erratic", "Fluctuating", "Medium Slow", "Fast", "Slow"
        };

        public GrowthRateComboBoxEditor() {
            super(new JComboBox<String>());
            JComboBox<String> comboBox = (JComboBox<String>) getComponent();
            comboBox.setFont(new Font("Segoe UI", Font.PLAIN, 11));
            for (String rate : GROWTH_RATES) {
                comboBox.addItem(rate);
            }
            EditorUtils.installSearchableComboBox(comboBox);
        }
    }

    private static class EggGroupComboBoxEditor extends DefaultCellEditor {
        private static final String[] EGG_GROUPS = {
                "None", "Monster", "Water 1", "Bug", "Flying", "Field", "Fairy",
                "Grass", "Human-Like", "Water 3", "Mineral", "Amorphous",
                "Water 2", "Ditto", "Dragon", "Undiscovered"
        };

        public EggGroupComboBoxEditor() {
            super(new JComboBox<String>());
            JComboBox<String> comboBox = (JComboBox<String>) getComponent();
            comboBox.setFont(new Font("Segoe UI", Font.PLAIN, 11));
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
     * Complete table model with all Pokemon data fields for Gen 6
     */
    private static class PokemonDataTableModel extends AbstractTableModel {
        static final int COL_ID = 0;
        static final int COL_NAME = 1;
        static final int COL_HP = 2;
        static final int COL_ATK = 3;
        static final int COL_DEF = 4;
        static final int COL_SPEED = 5;
        static final int COL_SP_ATK = 6;
        static final int COL_SP_DEF = 7;
        static final int COL_BST = 8;
        static final int COL_TYPE_1 = 9;
        static final int COL_TYPE_2 = 10;
        static final int COL_CATCH_RATE = 11;
        static final int COL_EXP_YIELD = 12;
        static final int COL_HP_EV = 13;
        static final int COL_ATK_EV = 14;
        static final int COL_DEF_EV = 15;
        static final int COL_SPEED_EV = 16;
        static final int COL_SP_ATK_EV = 17;
        static final int COL_SP_DEF_EV = 18;
        static final int COL_COMMON_ITEM = 19;
        static final int COL_RARE_ITEM = 20;
        static final int COL_GUARANTEED_ITEM = 21;
        // Gen6/Gen7 ROMs have only Common/Rare held-item slots (Guaranteed = both equal);
        // there is no Dark Grass / "Very Rare" slot, so that column is intentionally absent.
        static final int COL_GENDER_RATIO = 22;
        static final int COL_HATCH_COUNTER = 23;
        static final int COL_BASE_HAPPINESS = 24;
        static final int COL_GROWTH_RATE = 25;
        static final int COL_EGG_GROUP_1 = 26;
        static final int COL_EGG_GROUP_2 = 27;
        static final int COL_ABILITY_1 = 28;
        static final int COL_ABILITY_2 = 29;
        static final int COL_ABILITY_3 = 30;
        static final int COL_RUN_CHANCE = 31;
        static final int COL_HEIGHT = 32;
        static final int COL_WEIGHT = 33;
        static final int COL_COLOR = 34;
        static final int COL_FLIP = 35;

        private static final String[] COLUMN_NAMES = {
                "ID", "Name",
                "HP", "ATK", "DEF", "SPEED", "SP_ATK", "SP_DEF", "BST",
                "Type 1", "Type 2",
                "Catch Rate", "Exp Yield",
                "HP EV\nYield", "ATK EV\nYield", "DEF EV\nYield", "SPEED EV\nYield", "SP_ATK EV\nYield",
                "SP_DEF EV\nYield",
                "Common\nHeld Item", "Rare\nHeld Item", "Guaranteed\nItem",
                "Gender\nRatio",
                "Hatch\nCounter",
                "Base\nHappiness",
                "Growth\nRate",
                "Egg Group 1", "Egg Group 2",
                "Ability 1", "Ability 2", "Ability 3",
                "Run\nChance",
                "Height",
                "Weight",
                "Color",
                "Flip"
        };

        private final List<Species> pokemonList;
        private final RomHandler romHandler;
        private final List<Item> itemList;

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
            return COLUMN_NAMES.length;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMN_NAMES[column];
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            switch (columnIndex) {
                case COL_ID:
                case COL_BST:
                    return Integer.class;
                case COL_FLIP:
                    return Boolean.class;
                default:
                    return Object.class;
            }
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return columnIndex != COL_ID && columnIndex != COL_GUARANTEED_ITEM && columnIndex != COL_BST;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            Species p = pokemonList.get(rowIndex + 1);
            if (p == null) {
                return null;
            }

            switch (columnIndex) {
                case COL_ID:
                    return p.getNumber();
                case COL_NAME:
                    return EditorUtils.speciesNameWithSuffix(p);
                case COL_HP:
                    return p.getHp();
                case COL_ATK:
                    return p.getAttack();
                case COL_DEF:
                    return p.getDefense();
                case COL_SPEED:
                    return p.getSpeed();
                case COL_SP_ATK:
                    return p.getSpatk();
                case COL_SP_DEF:
                    return p.getSpdef();
                case COL_BST:
                    return p.getHp() + p.getAttack() + p.getDefense()
                            + p.getSpeed() + p.getSpatk() + p.getSpdef();
                case COL_TYPE_1:
                    return p.getPrimaryType(false) != null ? p.getPrimaryType(false).name() : "";
                case COL_TYPE_2:
                    Type secType = p.getSecondaryType(false);
                    return (secType != null && secType != p.getPrimaryType(false)) ? secType.name() : "";
                case COL_CATCH_RATE:
                    return p.getCatchRate();
                case COL_EXP_YIELD:
                    return p.getExpYield();
                case COL_HP_EV:
                    return p.getHpEvYield();
                case COL_ATK_EV:
                    return p.getAttackEvYield();
                case COL_DEF_EV:
                    return p.getDefenseEvYield();
                case COL_SPEED_EV:
                    return p.getSpeedEvYield();
                case COL_SP_ATK_EV:
                    return p.getSpatkEvYield();
                case COL_SP_DEF_EV:
                    return p.getSpdefEvYield();
                case COL_COMMON_ITEM:
                    // If guaranteed item is set, show it here (both slots have same item)
                    if (p.getGuaranteedHeldItem() != null) {
                        return p.getGuaranteedHeldItem().getName();
                    }
                    return p.getCommonHeldItem() != null ? p.getCommonHeldItem().getName() : "None";
                case COL_RARE_ITEM:
                    // If guaranteed item is set, show it here too (both slots have same item)
                    if (p.getGuaranteedHeldItem() != null) {
                        return p.getGuaranteedHeldItem().getName();
                    }
                    return p.getRareHeldItem() != null ? p.getRareHeldItem().getName() : "None";
                case COL_GUARANTEED_ITEM:
                    // Dynamically show guaranteed item (read-only)
                    return p.getGuaranteedHeldItem() != null ? p.getGuaranteedHeldItem().getName() : "None";
                case COL_GENDER_RATIO:
                    return p.getGenderRatio();
                case COL_HATCH_COUNTER:
                    return p.getHatchCounter();
                case COL_BASE_HAPPINESS:
                    return p.getBaseHappiness();
                case COL_GROWTH_RATE:
                    return formatGrowthRate(p.getGrowthCurve());
                case COL_EGG_GROUP_1:
                    return formatEggGroup(p.getEggGroup1());
                case COL_EGG_GROUP_2:
                    return formatEggGroup(p.getEggGroup2());
                case COL_ABILITY_1:
                    return formatAbility(p.getAbility1());
                case COL_ABILITY_2:
                    return formatAbility(p.getAbility2());
                case COL_ABILITY_3:
                    return formatAbility(p.getAbility3());
                case COL_RUN_CHANCE:
                    return p.getRunChance();
                case COL_HEIGHT:
                    return p.getHeight();
                case COL_WEIGHT:
                    return p.getWeight();
                case COL_COLOR:
                    return p.getColor();
                case COL_FLIP:
                    return p.isFlip();
                default:
                    return null;
            }
        }

        @Override
        public void setValueAt(Object aValue, int rowIndex, int columnIndex) {
            Species p = pokemonList.get(rowIndex + 1);
            if (p == null) {
                return;
            }

            Object val = aValue;

            switch (columnIndex) {
                case COL_NAME:
                    String newName = val == null ? "" : val.toString().trim();
                    String suffix = p.getFormeSuffix();
                    if (suffix != null && !suffix.isEmpty() && newName.endsWith(suffix)) {
                        newName = newName.substring(0, newName.length() - suffix.length()).trim();
                    }
                    if (!newName.isEmpty()) {
                        p.setName(newName);
                    }
                    break;
                case COL_HP:
                    p.setHp(parseBoundedInt(val, 1, 255));
                    break;
                case COL_ATK:
                    p.setAttack(parseBoundedInt(val, 1, 255));
                    break;
                case COL_DEF:
                    p.setDefense(parseBoundedInt(val, 1, 255));
                    break;
                case COL_SPEED:
                    p.setSpeed(parseBoundedInt(val, 1, 255));
                    break;
                case COL_SP_ATK:
                    p.setSpatk(parseBoundedInt(val, 1, 255));
                    break;
                case COL_SP_DEF:
                    p.setSpdef(parseBoundedInt(val, 1, 255));
                    break;
                case COL_BST:
                    // Read-only calculated column
                    break;
                case COL_TYPE_1:
                    if (val != null && !val.toString().isEmpty()) {
                        p.setPrimaryType(Type.valueOf(val.toString()));
                    }
                    break;
                case COL_TYPE_2:
                    if (val != null && !val.toString().isEmpty()) {
                        p.setSecondaryType(Type.valueOf(val.toString()));
                    } else {
                        p.setSecondaryType(null);
                    }
                    break;
                case COL_CATCH_RATE:
                    p.setCatchRate(parseBoundedInt(val, 0, 255));
                    break;
                case COL_EXP_YIELD:
                    p.setExpYield(parseBoundedInt(val, 0, 1000));
                    break;
                case COL_HP_EV:
                    p.setHpEvYield(parseBoundedInt(val, 0, 3));
                    break;
                case COL_ATK_EV:
                    p.setAttackEvYield(parseBoundedInt(val, 0, 3));
                    break;
                case COL_DEF_EV:
                    p.setDefenseEvYield(parseBoundedInt(val, 0, 3));
                    break;
                case COL_SPEED_EV:
                    p.setSpeedEvYield(parseBoundedInt(val, 0, 3));
                    break;
                case COL_SP_ATK_EV:
                    p.setSpatkEvYield(parseBoundedInt(val, 0, 3));
                    break;
                case COL_SP_DEF_EV:
                    p.setSpdefEvYield(parseBoundedInt(val, 0, 3));
                    break;
                case COL_COMMON_ITEM: {
                    Item newItem = findItemByName(val.toString());
                    // If we currently have a guaranteed item, we need to break it apart
                    if (p.getGuaranteedHeldItem() != null) {
                        Item oldGuaranteed = p.getGuaranteedHeldItem();
                        p.setGuaranteedHeldItem(null);
                        p.setRareHeldItem(oldGuaranteed); // Keep old guaranteed in rare slot
                    }
                    p.setCommonHeldItem(newItem);
                    updateGuaranteedItem(p); // Check if items now match
                }
                    break;
                case COL_RARE_ITEM: {
                    Item newItem = findItemByName(val.toString());
                    // If we currently have a guaranteed item, we need to break it apart
                    if (p.getGuaranteedHeldItem() != null) {
                        Item oldGuaranteed = p.getGuaranteedHeldItem();
                        p.setGuaranteedHeldItem(null);
                        p.setCommonHeldItem(oldGuaranteed); // Keep old guaranteed in common slot
                    }
                    p.setRareHeldItem(newItem);
                    updateGuaranteedItem(p); // Check if items now match
                }
                    break;
                case COL_GUARANTEED_ITEM:
                    // Read-only column, do nothing
                    break;
                case COL_GENDER_RATIO:
                    p.setGenderRatio(parseBoundedInt(val, 0, 255));
                    break;
                case COL_HATCH_COUNTER:
                    p.setHatchCounter(parseBoundedInt(val, 0, 255));
                    break;
                case COL_BASE_HAPPINESS:
                    p.setBaseHappiness(parseBoundedInt(val, 0, 255));
                    break;
                case COL_GROWTH_RATE:
                    p.setGrowthCurve(parseGrowthRate(val.toString()));
                    break;
                case COL_EGG_GROUP_1:
                    p.setEggGroup1(parseEggGroup(val.toString()));
                    break;
                case COL_EGG_GROUP_2:
                    p.setEggGroup2(parseEggGroup(val.toString()));
                    break;
                case COL_ABILITY_1:
                    p.setAbility1(parseAbility(val.toString()));
                    break;
                case COL_ABILITY_2:
                    p.setAbility2(parseAbility(val.toString()));
                    break;
                case COL_ABILITY_3:
                    p.setAbility3(parseAbility(val.toString()));
                    break;
                case COL_RUN_CHANCE:
                    p.setRunChance(parseBoundedInt(val, 0, 255));
                    break;
                case COL_HEIGHT:
                    p.setHeight(parseBoundedInt(val, 0, 65535));
                    break;
                case COL_WEIGHT:
                    p.setWeight(parseBoundedInt(val, 0, 65535));
                    break;
                case COL_COLOR:
                    p.setColor(parseBoundedInt(val, 0, 255));
                    break;
                case COL_FLIP:
                    if (val instanceof Boolean) {
                        p.setFlip((Boolean) val);
                    }
                    break;
            }

            fireTableCellUpdated(rowIndex, columnIndex);
        }

        private int parseInt(Object val) {
            if (val == null)
                return 0;
            try {
                return Integer.parseInt(val.toString().trim());
            } catch (NumberFormatException e) {
                return 0;
            }
        }

        private int parseBoundedInt(Object val, int min, int max) {
            int parsed = parseInt(val);
            if (parsed < min) {
                return min;
            }
            if (parsed > max) {
                return max;
            }
            return parsed;
        }

        private String formatGrowthRate(ExpCurve curve) {
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

        private ExpCurve parseGrowthRate(String str) {
            switch (str) {
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

        private static final String[] EGG_GROUP_NAMES = {
                "None", "Monster", "Water 1", "Bug", "Flying", "Field", "Fairy",
                "Grass", "Human-Like", "Water 3", "Mineral", "Amorphous",
                "Water 2", "Ditto", "Dragon", "Undiscovered"
        };

        private String formatEggGroup(int group) {
            if (group >= 0 && group < EGG_GROUP_NAMES.length) {
                return EGG_GROUP_NAMES[group];
            }
            return "None";
        }

        private int parseEggGroup(String str) {
            for (int i = 0; i < EGG_GROUP_NAMES.length; i++) {
                if (EGG_GROUP_NAMES[i].equals(str)) {
                    return i;
                }
            }
            return 0;
        }

        private String formatAbility(int abilityNum) {
            if (abilityNum == 0)
                return "0";
            try {
                String name = romHandler.abilityName(abilityNum);
                if (name != null && !name.isEmpty()) {
                    return abilityNum + ": " + name;
                }
            } catch (Exception e) {
                // Ignore
            }
            return String.valueOf(abilityNum);
        }

        private int parseAbility(String str) {
            if (str == null || str.trim().isEmpty())
                return 0;
            str = str.trim();
            if (str.contains(":")) {
                try {
                    return Integer.parseInt(str.substring(0, str.indexOf(":")).trim());
                } catch (NumberFormatException e) {
                    return 0;
                }
            }
            try {
                return Integer.parseInt(str);
            } catch (NumberFormatException e) {
                return 0;
            }
        }

        private Item findItemByName(String name) {
            if (name == null || name.equals("None"))
                return null;
            for (Item item : itemList) {
                if (item != null && item.getName().equals(name)) {
                    return item;
                }
            }
            return null;
        }

        /**
         * Updates the guaranteed item field dynamically.
         * If common and rare items are the same (and not null), set as guaranteed.
         * Otherwise, clear guaranteed item.
         */
        private void updateGuaranteedItem(Species p) {
            Item common = p.getCommonHeldItem();
            Item rare = p.getRareHeldItem();

            if (common != null && common.equals(rare)) {
                // Both items are the same, set as guaranteed
                p.setGuaranteedHeldItem(common);
            } else {
                // Items are different or one is null, clear guaranteed
                p.setGuaranteedHeldItem(null);
            }

            // Fire table update for the guaranteed item column
            int rowIndex = pokemonList.indexOf(p) - 1;
            if (rowIndex >= 0) {
                fireTableCellUpdated(rowIndex, COL_GUARANTEED_ITEM);
            }
        }
    }

    /**
     * Backup class for Species data
     */
    private static class SpeciesBackup {
        private int hp, attack, defense, speed, spatk, spdef;
        private Type primaryType, secondaryType;
        private int catchRate, expYield;
        private int hpEvYield, attackEvYield, defenseEvYield, speedEvYield, spatkEvYield, spdefEvYield;
        private Item commonHeldItem, rareHeldItem, guaranteedHeldItem;
        private int genderRatio, hatchCounter, baseHappiness;
        private ExpCurve growthCurve;
        private int eggGroup1, eggGroup2;
        private int ability1, ability2, ability3;
        private int runChance, height, weight, color;
        private boolean flip;

        public SpeciesBackup(Species p) {
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
            this.hatchCounter = p.getHatchCounter();
            this.baseHappiness = p.getBaseHappiness();
            this.growthCurve = p.getGrowthCurve();
            this.eggGroup1 = p.getEggGroup1();
            this.eggGroup2 = p.getEggGroup2();
            this.ability1 = p.getAbility1();
            this.ability2 = p.getAbility2();
            this.ability3 = p.getAbility3();
            this.runChance = p.getRunChance();
            this.height = p.getHeight();
            this.weight = p.getWeight();
            this.color = p.getColor();
            this.flip = p.isFlip();
        }

        public void restoreTo(Species p) {
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
            p.setHatchCounter(this.hatchCounter);
            p.setBaseHappiness(this.baseHappiness);
            p.setGrowthCurve(this.growthCurve);
            p.setEggGroup1(this.eggGroup1);
            p.setEggGroup2(this.eggGroup2);
            p.setAbility1(this.ability1);
            p.setAbility2(this.ability2);
            p.setAbility3(this.ability3);
            p.setRunChance(this.runChance);
            p.setHeight(this.height);
            p.setWeight(this.weight);
            p.setColor(this.color);
            p.setFlip(this.flip);
        }
    }
}
