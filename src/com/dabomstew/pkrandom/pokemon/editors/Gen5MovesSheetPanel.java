package com.dabomstew.pkrandom.pokemon.editors;

import com.dabomstew.pkrandom.log.ManualEditRegistry;
import com.dabomstew.pkromio.constants.GlobalConstants;
import com.dabomstew.pkromio.constants.Gen5Constants;
import com.dabomstew.pkromio.constants.MoveIDs;
import com.dabomstew.pkromio.gamedata.*;
import com.dabomstew.pkromio.romhandlers.RomHandler;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Moves Sheet editor matching PokEditor's EXACT implementation
 * Based on MovesTable.java from PokEditor
 */
public class Gen5MovesSheetPanel extends JPanel {

    private final RomHandler romHandler;
    private final List<Move> movesList;
    private JTable frozenTable;
    private JTable mainTable;
    private MovesDataTableModel tableModel;
    private boolean copyPasteModeEnabled = false;
    private final EditorUtils.FindState findState = new EditorUtils.FindState();

    private static final TargetOption[] MOVE_TARGET_OPTIONS = {
            new TargetOption(0x00, "Single Target"),
            new TargetOption(0x07, "Self"),
            new TargetOption(0x04, "All Adjacent Pokemon"),
            new TargetOption(0x03, "One Adjacent Enemy"),
            new TargetOption(0x09, "Single Adjacent Enemy"),
            new TargetOption(0x05, "All Adjacent Enemies"),
            new TargetOption(0x01, "Single Ally"),
            new TargetOption(0x02, "Single Adjacent Ally"),
            new TargetOption(0x06, "All Allies"),
            new TargetOption(0x08, "All Pokemon On Field"),
            new TargetOption(0x0A, "Entire Field"),
            new TargetOption(0x0B, "Opponent's Field"),
            new TargetOption(0x0C, "User's Field"),
            new TargetOption(0x0D, "Self (Protective)")
    };

    private static final String[] MOVE_TARGET_LABELS = Arrays.stream(MOVE_TARGET_OPTIONS)
            .map(option -> option.label)
            .toArray(String[]::new);

    private static final Map<String, Integer> LEGACY_TARGET_ALIASES = createLegacyTargetAliases();

    private static Map<String, Integer> createLegacyTargetAliases() {
        Map<String, Integer> aliases = new HashMap<>();
        addLegacyAlias(aliases, "Selected Pokemon", 0x00);
        addLegacyAlias(aliases, "Automatic", 0x07);
        addLegacyAlias(aliases, "Random", 0x09);
        addLegacyAlias(aliases, "Both Foes", 0x05);
        addLegacyAlias(aliases, "All Except User", 0x04);
        addLegacyAlias(aliases, "User", 0x07);
        addLegacyAlias(aliases, "User Side", 0x0C);
        addLegacyAlias(aliases, "Entire Field", 0x0A);
        addLegacyAlias(aliases, "Foe Side", 0x0B);
        addLegacyAlias(aliases, "Ally", 0x01);
        addLegacyAlias(aliases, "User or Ally", 0x02);
        addLegacyAlias(aliases, "Me First", 0x0D);
        return aliases;
    }

    private static void addLegacyAlias(Map<String, Integer> aliases, String label, int value) {
        aliases.put(label, value & 0xFF);
        aliases.put(label.toLowerCase(Locale.ROOT), value & 0xFF);
    }

    private static final CategoryQualityOption[] CATEGORY_QUALITY_OPTIONS = {
            new CategoryQualityOption(0, "Damage"),
            new CategoryQualityOption(4, "Dmg + Status Effect"),
            new CategoryQualityOption(6, "Dmg + Target Stat Change"),
            new CategoryQualityOption(7, "Dmg + User Stat Change"),
            new CategoryQualityOption(8, "Dmg + Life Steal"),
            new CategoryQualityOption(1, "Status Effect"),
            new CategoryQualityOption(2, "Stat Change"),
            new CategoryQualityOption(3, "Healing"),
            new CategoryQualityOption(5, "Status + Stat Change"),
            new CategoryQualityOption(10, "Effect On Field"),
            new CategoryQualityOption(11, "Effect On One Side"),
            new CategoryQualityOption(12, "Force Switch Out"),
            new CategoryQualityOption(9, "One Hit KO"),
            new CategoryQualityOption(13, "Unique Effect")
    };

    private static final StatusEffectOption[] STATUS_EFFECT_OPTIONS = {
            new StatusEffectOption(0, "None"),
            new StatusEffectOption(1, "Paralyze"),
            new StatusEffectOption(2, "Sleep"),
            new StatusEffectOption(3, "Freeze"),
            new StatusEffectOption(4, "Burn"),
            new StatusEffectOption(5, "Poison"),
            new StatusEffectOption(6, "Confusion"),
            new StatusEffectOption(7, "Attract"),
            new StatusEffectOption(8, "Trap"),
            new StatusEffectOption(9, "Nightmare"),
            new StatusEffectOption(10, "Curse"),
            new StatusEffectOption(11, "Taunt"),
            new StatusEffectOption(12, "Torment"),
            new StatusEffectOption(13, "Disable"),
            new StatusEffectOption(14, "Yawn"),
            new StatusEffectOption(15, "Heal Block"),
            new StatusEffectOption(16, "?"),
            new StatusEffectOption(17, "Detect"),
            new StatusEffectOption(18, "Leech Seed"),
            new StatusEffectOption(19, "Embargo"),
            new StatusEffectOption(20, "Perish Song"),
            new StatusEffectOption(21, "Ingrain"),
            new StatusEffectOption(-1, "Special")
    };

    private static class TargetOption {
        final int value;
        final String label;

        TargetOption(int value, String label) {
            this.value = value & 0xFF;
            this.label = label;
        }
    }

    private static class CategoryQualityOption {
        final int id;
        final String label;

        CategoryQualityOption(int id, String label) {
            this.id = id;
            this.label = label;
        }
    }

    private static class StatusEffectOption {
        final int id;
        final String label;

        StatusEffectOption(int id, String label) {
            this.id = id;
            this.label = label;
        }
    }

    private final Map<Move, MoveBackup> backupData = new HashMap<>();

    public Gen5MovesSheetPanel(RomHandler romHandler) {
        this.romHandler = romHandler;
        this.movesList = romHandler.getMoves();
        initializeUI();
        createBackup();
    }

    private void initializeUI() {
        setLayout(new BorderLayout());
        setBackground(EditorTheme.surface());

        // Create toolbar
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

        JLabel infoLabel = new JLabel("Edit move data directly in the table");
        infoLabel.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        infoLabel.setForeground(EditorTheme.mutedText());
        toolbar.add(infoLabel);

        return toolbar;
    }

    private JPanel createFrozenColumnTable() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(EditorTheme.surface());

        tableModel = new MovesDataTableModel(movesList, romHandler);

        // Frozen table (ID and Name)
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

            public boolean isCellEditable(int row, int col) {
                if (copyPasteModeEnabled) {
                    return false;
                }
                return col == 1; // Name is editable
            }

            public void setValueAt(Object val, int row, int col) {
                tableModel.setValueAt(val, row, col);
            }
        };

        frozenTable = new JTable(frozenModel);
        styleTable(frozenTable, true);
        TableLayoutDefaults.configureFrozenColumns(frozenTable.getColumnModel(), false);

        // Main scrollable table
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

        TableLayoutDefaults.applyRowHeight(frozenTable, false);
        TableLayoutDefaults.applyRowHeight(mainTable, false);

        // The JTables listen to the frozen/main wrapper models, but a cell edit fires its
        // change event on the inner tableModel (and several Gen5 setters update OTHER derived
        // columns in the same row: min/max hits, trap, status type, crit). Bridge those events
        // to a repaint of both visible tables so derived columns refresh immediately instead of
        // showing stale values (Feature #35).
        tableModel.addTableModelListener(e -> {
            if (frozenTable != null) {
                frozenTable.repaint();
            }
            if (mainTable != null) {
                mainTable.repaint();
            }
        });

        // Sync row selection between frozen and main tables
        frozenTable.setSelectionModel(mainTable.getSelectionModel());
        frozenTable.getColumnModel().getSelectionModel().setSelectionMode(ListSelectionModel.SINGLE_INTERVAL_SELECTION);
        mainTable.getColumnModel().getSelectionModel().setSelectionMode(ListSelectionModel.SINGLE_INTERVAL_SELECTION);
        EditorUtils.installFrozenColumnSync(frozenTable, mainTable);

        // Sync scrolling between tables
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
        int frozenWidth = TableLayoutDefaults.frozenPanelWidth(false);
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

    private List<String> collectMoveChangesForLog() {
        List<String> changes = new ArrayList<>();
        for (Move move : movesList) {
            if (move == null) {
                continue;
            }
            MoveBackup backup = backupData.get(move);
            if (backup == null) {
                continue;
            }
            Move before = backup.getSnapshot();
            if (moveHasChanged(before, move)) {
                String name = move.name != null ? move.name : "Move";
                changes.add(name + " (#" + move.number + ") updated");
            }
        }
        return changes;
    }

    private boolean moveHasChanged(Move before, Move after) {
        if (!Objects.equals(before.name, after.name))
            return true;
        if (before.effectIndex != after.effectIndex)
            return true;
        if (before.category != after.category)
            return true;
        if (before.categoryQuality != after.categoryQuality)
            return true;
        if (before.power != after.power)
            return true;
        if (!Objects.equals(before.type, after.type))
            return true;
        if ((int) before.hitratio != (int) after.hitratio)
            return true;
        if (before.pp != after.pp)
            return true;
        if (before.secondaryEffectChance != after.secondaryEffectChance)
            return true;
        if (before.target != after.target)
            return true;
        if (before.priority != after.priority)
            return true;
        if (!Objects.equals(before.statusType, after.statusType))
            return true;
        if ((int) Math.round(before.statusPercentChance) != (int) Math.round(after.statusPercentChance))
            return true;
        if ((int) Math.round(before.flinchPercentChance) != (int) Math.round(after.flinchPercentChance))
            return true;
        if (before.criticalChance != after.criticalChance)
            return true;
        if (before.criticalStage != after.criticalStage)
            return true;
        if (before.recoilPercent != after.recoilPercent)
            return true;
        if (before.absorbPercent != after.absorbPercent)
            return true;
        if (before.minHits != after.minHits)
            return true;
        if (before.maxHits != after.maxHits)
            return true;
        if (before.minTrapTurns != after.minTrapTurns)
            return true;
        if (before.maxTrapTurns != after.maxTrapTurns)
            return true;
        if (before.statusEffect != after.statusEffect)
            return true;
        if (before.makesContact != after.makesContact)
            return true;
        if (before.isProtectedFromProtect != after.isProtectedFromProtect)
            return true;
        if (before.isMagicCoatAffected != after.isMagicCoatAffected)
            return true;
        if (before.isSnatchAffected != after.isSnatchAffected)
            return true;
        if (before.isMirrorMoveAffected != after.isMirrorMoveAffected)
            return true;
        if (before.isFlinchMove != after.isFlinchMove)
            return true;
        if (before.isChargeMove != after.isChargeMove)
            return true;
        if (before.isRechargeMove != after.isRechargeMove)
            return true;
        if (before.isPunchMove != after.isPunchMove)
            return true;
        if (before.isSoundMove != after.isSoundMove)
            return true;
        if (before.isTrapMove != after.isTrapMove)
            return true;
        if (before.hidesHpBars != after.hidesHpBars)
            return true;
        if (before.removesTargetShadow != after.removesTargetShadow)
            return true;
        if (before.groundedByGravity != after.groundedByGravity)
            return true;
        if (before.defrostsUser != after.defrostsUser)
            return true;
        if (before.hitsNonAdjacent != after.hitsNonAdjacent)
            return true;
        if (before.isHealingMove != after.isHealingMove)
            return true;
        if (before.bypassesSubstitute != after.bypassesSubstitute)
            return true;
        if (before.extraFlag1 != after.extraFlag1)
            return true;
        if (before.extraFlag2 != after.extraFlag2)
            return true;
        if (!statChangesEqual(before.statChanges, after.statChanges))
            return true;
        if (before.contestEffect != after.contestEffect)
            return true;
        if (before.contestType != after.contestType)
            return true;
        return false;
    }

    private boolean statChangesEqual(Move.StatChange[] before, Move.StatChange[] after) {
        if (before == after) {
            return true;
        }
        if (before == null || after == null) {
            return false;
        }
        if (before.length != after.length) {
            return false;
        }
        for (int i = 0; i < before.length; i++) {
            Move.StatChange b = before[i];
            Move.StatChange a = after[i];
            if (b == null && a == null) {
                continue;
            }
            if (b == null || a == null) {
                return false;
            }
            if (b.type != a.type) {
                return false;
            }
            if (b.stages != a.stages) {
                return false;
            }
            if ((int) Math.round(b.percentChance) != (int) Math.round(a.percentChance)) {
                return false;
            }
        }
        return true;
    }

    private void createBackup() {
        backupData.clear();
        for (Move move : movesList) {
            if (move != null) {
                backupData.put(move, new MoveBackup(move));
            }
        }
    }

    private void restoreFromBackup() {
        for (Move move : movesList) {
            if (move != null) {
                MoveBackup backup = backupData.get(move);
                if (backup != null) {
                    backup.restoreTo(move);
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

    private void styleTable(JTable table, boolean isFrozen) {
        if (isFrozen) {
            TableLayoutDefaults.applySheetTableStyle(table, true, 1);
        } else {
            TableLayoutDefaults.applySheetTableStyle(table, false);
        }
        TableLayoutDefaults.applyRowHeight(table, false);
    }

    private void setupMainTableColumns() {
        TableColumnModel columnModel = mainTable.getColumnModel();
        for (int i = 0; i < mainTable.getColumnCount(); i++) {
            TableColumn column = columnModel.getColumn(i);
            int modelCol = i + 2; // shift for frozen ID/Name columns
            int width = getColumnWidth(modelCol);
            column.setPreferredWidth(width);
            column.setMinWidth(Math.max(60, width - 80));
            column.setMaxWidth(width + 160);
            column.setWidth(width);

            if (modelCol == MovesDataTableModel.COL_CATEGORY) {
                column.setCellEditor(new CategoryComboBoxEditor());
            } else if (modelCol == MovesDataTableModel.COL_CATEGORY_QUALITY) {
                column.setCellEditor(new CategoryQualityComboBoxEditor());
            } else if (modelCol == MovesDataTableModel.COL_TYPE) {
                column.setCellRenderer(new TypeCellRenderer());
                column.setCellEditor(new TypeComboBoxEditor(romHandler));
            } else if (modelCol == MovesDataTableModel.COL_TARGET) {
                column.setCellEditor(new TargetComboBoxEditor());
            } else if (modelCol == MovesDataTableModel.COL_STATUS_EFFECT) {
                column.setCellEditor(new StatusEffectComboBoxEditor());
            } else if (modelCol == MovesDataTableModel.COL_STATUS_TYPE) {
                column.setCellEditor(new StatusComboBoxEditor());
            } else if (modelCol == MovesDataTableModel.COL_CRIT_STAGE) {
                column.setCellEditor(new IntegerSpinnerEditor(0, 255));
            } else if (modelCol == MovesDataTableModel.COL_STAT1_STAT
                    || modelCol == MovesDataTableModel.COL_STAT2_STAT
                    || modelCol == MovesDataTableModel.COL_STAT3_STAT) {
                column.setCellEditor(new StatChangeComboBoxEditor());
            } else if (modelCol >= MovesDataTableModel.COL_MAKES_CONTACT
                    && modelCol <= MovesDataTableModel.COL_REMOVE_SHADOW) {
                column.setCellEditor(new StableCheckBoxEditor());
                column.setCellRenderer(new CheckBoxRenderer());
            }
        }

        mainTable.doLayout();
        mainTable.getTableHeader().setPreferredSize(new Dimension(
                mainTable.getTableHeader().getPreferredSize().width, 60));
    }

    private int getColumnWidth(int modelCol) {
        switch (modelCol) {
            case MovesDataTableModel.COL_EFFECT:
                return 240;
            case MovesDataTableModel.COL_CATEGORY:
                return 140;
            case MovesDataTableModel.COL_CATEGORY_QUALITY:
                return 180;
            case MovesDataTableModel.COL_POWER:
                return 90;
            case MovesDataTableModel.COL_TYPE:
                return 150;
            case MovesDataTableModel.COL_ACCURACY:
                return 90;
            case MovesDataTableModel.COL_PP:
                return 90;
            case MovesDataTableModel.COL_TARGET:
                return 220;
            case MovesDataTableModel.COL_PRIORITY:
                return 90;
            case MovesDataTableModel.COL_MIN_HITS:
            case MovesDataTableModel.COL_MAX_HITS:
                return 95;
            case MovesDataTableModel.COL_MIN_TRAP_TURNS:
            case MovesDataTableModel.COL_MAX_TRAP_TURNS:
                return 130;
            case MovesDataTableModel.COL_STATUS_EFFECT:
                return 170;
            case MovesDataTableModel.COL_STATUS_TYPE:
                return 160;
            case MovesDataTableModel.COL_STATUS_CHANCE:
            case MovesDataTableModel.COL_FLINCH_CHANCE:
            case MovesDataTableModel.COL_RECOIL:
            case MovesDataTableModel.COL_HEAL:
                return 95;
            case MovesDataTableModel.COL_CRIT_STAGE:
                return 110;
            case MovesDataTableModel.COL_STAT1_STAT:
            case MovesDataTableModel.COL_STAT2_STAT:
            case MovesDataTableModel.COL_STAT3_STAT:
                return 160;
            case MovesDataTableModel.COL_STAT1_STAGES:
            case MovesDataTableModel.COL_STAT2_STAGES:
            case MovesDataTableModel.COL_STAT3_STAGES:
            case MovesDataTableModel.COL_STAT1_CHANCE:
            case MovesDataTableModel.COL_STAT2_CHANCE:
            case MovesDataTableModel.COL_STAT3_CHANCE:
                return 110;
            case MovesDataTableModel.COL_CONTEST_EFFECT:
            case MovesDataTableModel.COL_CONTEST_TYPE:
                return 110;
            default:
                if (modelCol >= MovesDataTableModel.COL_MAKES_CONTACT
                        && modelCol <= MovesDataTableModel.COL_REMOVE_SHADOW) {
                    return 140;
                }
                return 140;
        }
    }

    public void save() {
        stopEditing();
        ManualEditRegistry.getInstance().addEntries("Moves Data", collectMoveChangesForLog());
        if (!EditorUtils.suppressSaveDialogs) {
            JOptionPane.showMessageDialog(this,
                    "Moves updated successfully!",
                    "Save Complete",
                    JOptionPane.INFORMATION_MESSAGE);
        }
        commitChanges();
    }

    private void reload() {
        stopEditing();
        restoreFromBackup();
    }

    private void exportToCSV() {
        stopEditing();
        EditorUtils.exportTableToCSV(this, tableModel, "Moves Sheet");
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

        EditorUtils.CsvData csvData = EditorUtils.chooseCsvFile(this, "Moves Sheet");
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
                    "Copy/Paste Mode ON\n\n" +
                            "- Tables are now read-only\n" +
                            "- Select cells and press Ctrl+C to copy\n" +
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

    // Renderers and Editors
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
                    c.setBackground(
                            row % 2 == 0 ? TableLayoutDefaults.evenRowColor() : TableLayoutDefaults.oddRowColor());
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

    private static class CheckBoxRenderer extends JCheckBox implements TableCellRenderer {
        public CheckBoxRenderer() {
            setHorizontalAlignment(SwingConstants.CENTER);
            setVerticalAlignment(SwingConstants.CENTER);
            setOpaque(true);
            setBorderPainted(true);
            setFocusPainted(false);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                boolean isSelected, boolean hasFocus, int row, int column) {
            // Safely handle different value types
            boolean checked = false;
            if (value instanceof Boolean) {
                checked = (Boolean) value;
            } else if (value != null) {
                // Try to parse string values
                String strValue = value.toString().toLowerCase();
                checked = strValue.equals("true") || strValue.equals("1");
            }

            setSelected(checked);
            setEnabled(true);

            // Set background color
            if (isSelected) {
                setBackground(table.getSelectionBackground());
                setForeground(table.getSelectionForeground());
            } else {
                setBackground(row % 2 == 0 ? TableLayoutDefaults.evenRowColor() : TableLayoutDefaults.oddRowColor());
                setForeground(EditorTheme.text());
            }

            // Remove focus border to prevent flickering
            setBorder(BorderFactory.createEmptyBorder(1, 1, 1, 1));

            return this;
        }
    }

    /**
     * Stable checkbox editor that doesn't flicker or move when clicked
     */
    private static class StableCheckBoxEditor extends AbstractCellEditor implements TableCellEditor {
        private final JCheckBox checkBox;

        public StableCheckBoxEditor() {
            checkBox = new JCheckBox();
            checkBox.setHorizontalAlignment(SwingConstants.CENTER);
            checkBox.setVerticalAlignment(SwingConstants.CENTER);
            checkBox.setOpaque(true);
            checkBox.setBorderPainted(false);
            checkBox.setFocusPainted(false);
            checkBox.setBackground(EditorTheme.surface());

            // Toggle on click - this prevents the "jumping" behavior
            checkBox.addActionListener(e -> {
                // Stop editing immediately after toggle
                fireEditingStopped();
            });
        }

        @Override
        public Component getTableCellEditorComponent(JTable table, Object value,
                boolean isSelected, int row, int column) {
            // Parse the current value
            boolean checked = false;
            if (value instanceof Boolean) {
                checked = (Boolean) value;
            } else if (value != null) {
                String strValue = value.toString().toLowerCase();
                checked = strValue.equals("true") || strValue.equals("1");
            }

            checkBox.setSelected(checked);

            // Keep the selection background visible when editing
            if (isSelected) {
                checkBox.setBackground(table.getSelectionBackground());
                checkBox.setForeground(table.getSelectionForeground());
            } else {
                checkBox.setBackground(
                        row % 2 == 0 ? TableLayoutDefaults.evenRowColor() : TableLayoutDefaults.oddRowColor());
                checkBox.setForeground(EditorTheme.text());
            }

            return checkBox;
        }

        @Override
        public Object getCellEditorValue() {
            return checkBox.isSelected();
        }
    }

    private static class CategoryComboBoxEditor extends DefaultCellEditor {
        // Matching PokEditor's category keys from MovesTable.java line 17
        private static final String[] CATEGORIES = { "Physical", "Special", "Status" };

        public CategoryComboBoxEditor() {
            super(new JComboBox<String>());
            JComboBox<String> comboBox = (JComboBox<String>) getComponent();
            comboBox.setFont(new Font("Segoe UI", Font.PLAIN, 11));
            for (String category : CATEGORIES) {
                comboBox.addItem(category);
            }
            EditorUtils.installSearchableComboBox(comboBox);
        }
    }

    private class TypeComboBoxEditor extends DefaultCellEditor {
        public TypeComboBoxEditor(RomHandler romHandler) {
            super(new JComboBox<String>());
            JComboBox<String> comboBox = (JComboBox<String>) getComponent();
            comboBox.setFont(new Font("Segoe UI", Font.PLAIN, 11));
            // Use the ROM's actual type list instead of hardcoded Type.values()
            // This respects Gen 5 vanilla (17 types) vs modded with Fairy (18 types)
            for (Type type : romHandler.getTypeTable().getTypes()) {
                comboBox.addItem(type.name());
            }
            EditorUtils.installSearchableComboBox(comboBox);
        }
    }

    private static class TargetComboBoxEditor extends DefaultCellEditor {
        public TargetComboBoxEditor() {
            super(new JComboBox<String>());
            JComboBox<String> comboBox = (JComboBox<String>) getComponent();
            comboBox.setFont(new Font("Segoe UI", Font.PLAIN, 11));
            for (String target : MOVE_TARGET_LABELS) {
                comboBox.addItem(target);
            }
            EditorUtils.installSearchableComboBox(comboBox);
        }
    }

    private static class StatusComboBoxEditor extends DefaultCellEditor {
        private static final String[] STATUSES = {
                "None", "Paralyze", "Sleep", "Freeze", "Burn", "Poison", "Confusion", "Toxic Poison"
        };

        public StatusComboBoxEditor() {
            super(new JComboBox<String>());
            JComboBox<String> comboBox = (JComboBox<String>) getComponent();
            comboBox.setFont(new Font("Segoe UI", Font.PLAIN, 11));
            for (String status : STATUSES) {
                comboBox.addItem(status);
            }
            EditorUtils.installSearchableComboBox(comboBox);
        }
    }

    private static class CategoryQualityComboBoxEditor extends DefaultCellEditor {
        public CategoryQualityComboBoxEditor() {
            super(new JComboBox<String>());
            JComboBox<String> comboBox = (JComboBox<String>) getComponent();
            comboBox.setFont(new Font("Segoe UI", Font.PLAIN, 11));
            comboBox.setEditable(true);
            for (CategoryQualityOption option : CATEGORY_QUALITY_OPTIONS) {
                comboBox.addItem(option.label);
            }
            EditorUtils.installSearchableComboBox(comboBox);
        }
    }

    private static class StatusEffectComboBoxEditor extends DefaultCellEditor {
        public StatusEffectComboBoxEditor() {
            super(new JComboBox<String>());
            JComboBox<String> comboBox = (JComboBox<String>) getComponent();
            comboBox.setFont(new Font("Segoe UI", Font.PLAIN, 11));
            comboBox.setEditable(true);
            for (StatusEffectOption option : STATUS_EFFECT_OPTIONS) {
                comboBox.addItem(option.label);
            }
            EditorUtils.installSearchableComboBox(comboBox);
        }
    }

    private static class IntegerSpinnerEditor extends AbstractCellEditor implements TableCellEditor {
        private final JSpinner spinner;
        private final int min;
        private final int max;

        public IntegerSpinnerEditor(int min, int max) {
            this.min = min;
            this.max = max;
            this.spinner = new JSpinner(new SpinnerNumberModel(min, min, max, 1));
            spinner.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        }

        @Override
        public Object getCellEditorValue() {
            return spinner.getValue();
        }

        @Override
        public Component getTableCellEditorComponent(JTable table, Object value, boolean isSelected, int row,
                int column) {
            int current = min;
            if (value instanceof Number) {
                current = ((Number) value).intValue();
            } else if (value != null) {
                try {
                    current = Integer.parseInt(value.toString());
                } catch (NumberFormatException ignored) {
                }
            }
            current = Math.max(min, Math.min(max, current));
            spinner.setValue(current);
            return spinner;
        }
    }

    private static class StatChangeComboBoxEditor extends DefaultCellEditor {
        private static final String[] STAT_OPTIONS = {
                "None", "Attack", "Defense", "Sp. Atk", "Sp. Def", "Speed", "Accuracy", "Evasion", "All", "Special"
        };

        public StatChangeComboBoxEditor() {
            super(new JComboBox<String>());
            JComboBox<String> comboBox = (JComboBox<String>) getComponent();
            comboBox.setFont(new Font("Segoe UI", Font.PLAIN, 11));
            for (String option : STAT_OPTIONS) {
                comboBox.addItem(option);
            }
            EditorUtils.installSearchableComboBox(comboBox);
        }
    }

    private static class MoveBackup {
        private final Move snapshot;

        MoveBackup(Move move) {
            this.snapshot = new Move(move);
        }

        void restoreTo(Move move) {
            copyMove(snapshot, move);
        }

        Move getSnapshot() {
            return new Move(snapshot);
        }

        private static void copyMove(Move source, Move target) {
            target.name = source.name;
            target.number = source.number;
            target.internalId = source.internalId;
            target.power = source.power;
            target.pp = source.pp;
            target.hitratio = source.hitratio;
            target.type = source.type;
            target.category = source.category;
            target.statChangeMoveType = source.statChangeMoveType;

            target.statChanges = new Move.StatChange[source.statChanges.length];
            for (int i = 0; i < source.statChanges.length; i++) {
                target.statChanges[i] = Move.StatChange.copy(source.statChanges[i]);
            }

            target.statusMoveType = source.statusMoveType;
            target.statusType = source.statusType;
            target.criticalChance = source.criticalChance;
            target.criticalStage = source.criticalStage;
            target.statusPercentChance = source.statusPercentChance;
            target.flinchPercentChance = source.flinchPercentChance;
            target.recoilPercent = source.recoilPercent;
            target.absorbPercent = source.absorbPercent;
            target.priority = source.priority;
            target.makesContact = source.makesContact;
            target.isChargeMove = source.isChargeMove;
            target.isRechargeMove = source.isRechargeMove;
            target.isPunchMove = source.isPunchMove;
            target.isSoundMove = source.isSoundMove;
            target.isTrapMove = source.isTrapMove;
            target.effectIndex = source.effectIndex;
            target.target = source.target;
            target.hitCount = source.hitCount;
            target.isProtectedFromProtect = source.isProtectedFromProtect;
            target.isMagicCoatAffected = source.isMagicCoatAffected;
            target.isSnatchAffected = source.isSnatchAffected;
            target.isMirrorMoveAffected = source.isMirrorMoveAffected;
            target.isFlinchMove = source.isFlinchMove;
            target.hidesHpBars = source.hidesHpBars;
            target.removesTargetShadow = source.removesTargetShadow;
            target.minHits = source.minHits;
            target.maxHits = source.maxHits;
            target.minTrapTurns = source.minTrapTurns;
            target.maxTrapTurns = source.maxTrapTurns;
            target.statusEffect = source.statusEffect;
            target.categoryQuality = source.categoryQuality;
            target.groundedByGravity = source.groundedByGravity;
            target.defrostsUser = source.defrostsUser;
            target.hitsNonAdjacent = source.hitsNonAdjacent;
            target.isHealingMove = source.isHealingMove;
            target.bypassesSubstitute = source.bypassesSubstitute;
            target.extraFlag1 = source.extraFlag1;
            target.extraFlag2 = source.extraFlag2;
            target.secondaryEffectChance = source.secondaryEffectChance;
            target.contestEffect = source.contestEffect;
            target.contestType = source.contestType;
        }
    }

    /**
     * Table model for moves data matching PokEditor's EXACT field layout.
     */
    private static class MovesDataTableModel extends AbstractTableModel {
        static final int COL_ID = 0;
        static final int COL_NAME = 1;
        static final int COL_EFFECT = 2;
        static final int COL_CATEGORY = 3;
        static final int COL_CATEGORY_QUALITY = 4;
        static final int COL_POWER = 5;
        static final int COL_TYPE = 6;
        static final int COL_ACCURACY = 7;
        static final int COL_PP = 8;
        static final int COL_TARGET = 9;
        static final int COL_PRIORITY = 10;
        static final int COL_MIN_HITS = 11;
        static final int COL_MAX_HITS = 12;
        static final int COL_MIN_TRAP_TURNS = 13;
        static final int COL_MAX_TRAP_TURNS = 14;
        static final int COL_STATUS_EFFECT = 15;
        static final int COL_STATUS_TYPE = 16;
        static final int COL_STATUS_CHANCE = 17;
        static final int COL_FLINCH_CHANCE = 18;
        static final int COL_CRIT_STAGE = 19;
        static final int COL_RECOIL = 20;
        static final int COL_HEAL = 21;
        static final int COL_STAT1_STAT = 22;
        static final int COL_STAT1_STAGES = 23;
        static final int COL_STAT1_CHANCE = 24;
        static final int COL_STAT2_STAT = 25;
        static final int COL_STAT2_STAGES = 26;
        static final int COL_STAT2_CHANCE = 27;
        static final int COL_STAT3_STAT = 28;
        static final int COL_STAT3_STAGES = 29;
        static final int COL_STAT3_CHANCE = 30;
        static final int COL_MAKES_CONTACT = 31;
        static final int COL_AFFECTED_BY_PROTECT = 32;
        static final int COL_REFLECTED_BY_MAGIC_COAT = 33;
        static final int COL_AFFECTED_BY_SNATCH = 34;
        static final int COL_AFFECTED_BY_MIRROR_MOVE = 35;
        static final int COL_CHARGE_MOVE = 36;
        static final int COL_RECHARGE_MOVE = 37;
        static final int COL_PUNCH_MOVE = 38;
        static final int COL_SOUND_MOVE = 39;
        static final int COL_GROUNDED_BY_GRAVITY = 40;
        static final int COL_DEFROSTS_USER = 41;
        static final int COL_HITS_NON_ADJACENT = 42;
        static final int COL_HEALING_MOVE_FLAG = 43;
        static final int COL_BYPASSES_SUBSTITUTE = 44;
        static final int COL_EXTRA_FLAG1 = 45;
        static final int COL_EXTRA_FLAG2 = 46;
        static final int COL_TRAP_MOVE = 47;
        static final int COL_HIDES_HP_BARS = 48;
        static final int COL_REMOVE_SHADOW = 49;
        static final int COL_CONTEST_EFFECT = 50;
        static final int COL_CONTEST_TYPE = 51;

        private static final String[] COLUMN_NAMES = {
                "ID", "Name",
                "Effect", "Category",
                "<html><center>Category<br>Quality</center></html>",
                "Power", "Type", "Accuracy", "PP",
                "Target", "Priority",
                "<html><center>Min<br>Hits</center></html>",
                "<html><center>Max<br>Hits</center></html>",
                "<html><center>Min Trap<br>Turns</center></html>",
                "<html><center>Max Trap<br>Turns</center></html>",
                "<html><center>Status<br>Effect</center></html>",
                "<html><center>Status<br>Type</center></html>",
                "<html><center>Status<br>Chance %</center></html>",
                "<html><center>Flinch<br>Chance %</center></html>",
                "<html><center>Crit<br>Stage</center></html>",
                "<html><center>Recoil /<br>Leech %</center></html>",
                "<html><center>Heal<br>%</center></html>",
                "<html><center>Stat Chg 1<br>Stat</center></html>",
                "<html><center>Stat Chg 1<br>Stages</center></html>",
                "<html><center>Stat Chg 1<br>Chance %</center></html>",
                "<html><center>Stat Chg 2<br>Stat</center></html>",
                "<html><center>Stat Chg 2<br>Stages</center></html>",
                "<html><center>Stat Chg 2<br>Chance %</center></html>",
                "<html><center>Stat Chg 3<br>Stat</center></html>",
                "<html><center>Stat Chg 3<br>Stages</center></html>",
                "<html><center>Stat Chg 3<br>Chance %</center></html>",
                "<html><center>Makes<br>Contact</center></html>",
                "<html><center>Affected by<br>Protect</center></html>",
                "<html><center>Reflected by<br>Magic Coat</center></html>",
                "<html><center>Affected by<br>Snatch</center></html>",
                "<html><center>Copied by<br>Mirror Move</center></html>",
                "<html><center>Charge<br>Move</center></html>",
                "<html><center>Recharge<br>Move</center></html>",
                "<html><center>Punch<br>Move</center></html>",
                "<html><center>Sound<br>Move</center></html>",
                "<html><center>Grounded by<br>Gravity</center></html>",
                "<html><center>Defrosts<br>User</center></html>",
                "<html><center>Hits Non-<br>Adjacent</center></html>",
                "<html><center>Healing<br>Move</center></html>",
                "<html><center>Bypasses<br>Substitute</center></html>",
                "<html><center>Extra<br>Flag 1</center></html>",
                "<html><center>Extra<br>Flag 2</center></html>",
                "<html><center>Trap<br>Move</center></html>",
                "<html><center>Hides<br>HP Bars</center></html>",
                "<html><center>Remove Target<br>Shadow</center></html>",
                "<html><center>Contest<br>Effect</center></html>",
                "<html><center>Contest<br>Type</center></html>"
        };

        private final List<Move> movesList;
        private final RomHandler romHandler;

        public MovesDataTableModel(List<Move> movesList, RomHandler romHandler) {
            this.movesList = movesList;
            this.romHandler = romHandler;
        }

        @Override
        public int getRowCount() {
            // movesList is 1-based (index 0 is a null placeholder), so skip it
            return Math.max(0, movesList.size() - 1);
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
        public Class<?> getColumnClass(int col) {
            if (isBooleanColumn(col)) {
                return Boolean.class;
            }
            if (isStringColumn(col)) {
                return String.class;
            }
            return Integer.class;
        }

        private boolean isBooleanColumn(int col) {
            return col >= COL_MAKES_CONTACT && col <= COL_REMOVE_SHADOW;
        }

        private boolean isStringColumn(int col) {
            return col == COL_NAME || col == COL_CATEGORY || col == COL_CATEGORY_QUALITY || col == COL_TYPE
                    || col == COL_TARGET
                    || col == COL_STATUS_EFFECT || col == COL_STATUS_TYPE
                    || col == COL_STAT1_STAT || col == COL_STAT2_STAT || col == COL_STAT3_STAT;
        }

        @Override
        public boolean isCellEditable(int row, int col) {
            return col > COL_ID;
        }

        @Override
        public Object getValueAt(int row, int col) {
            if (row + 1 >= movesList.size()) {
                return defaultValueForColumn(col);
            }
            Move move = movesList.get(row + 1);
            if (move == null) {
                return defaultValueForColumn(col);
            }
            switch (col) {
                case COL_ID:
                    return move.number;
                case COL_NAME:
                    return move.name != null ? move.name : "";
                case COL_EFFECT:
                    return move.effectIndex;
                case COL_CATEGORY:
                    return getCategoryName(move.category);
                case COL_CATEGORY_QUALITY:
                    return getCategoryQualityName(move.categoryQuality);
                case COL_POWER:
                    return move.power;
                case COL_TYPE:
                    return move.type != null ? move.type.name() : "";
                case COL_ACCURACY:
                    return (int) Math.round(move.hitratio);
                case COL_PP:
                    return move.pp;
                case COL_TARGET:
                    return getTargetName(move.target);
                case COL_PRIORITY:
                    return move.priority;
                case COL_MIN_HITS:
                    return move.minHits;
                case COL_MAX_HITS:
                    return move.maxHits;
                case COL_MIN_TRAP_TURNS:
                    return move.minTrapTurns;
                case COL_MAX_TRAP_TURNS:
                    return move.maxTrapTurns;
                case COL_STATUS_EFFECT:
                    return getStatusEffectName(move.statusEffect);
                case COL_STATUS_TYPE:
                    return getStatusTypeName(move.statusType);
                case COL_STATUS_CHANCE:
                    return (int) Math.round(move.statusPercentChance);
                case COL_FLINCH_CHANCE:
                    return (int) Math.round(move.flinchPercentChance);
                case COL_CRIT_STAGE:
                    return move.criticalStage;
                case COL_RECOIL:
                    return move.recoilPercent;
                case COL_HEAL:
                    return move.absorbPercent;
                case COL_STAT1_STAT:
                    return getStatChangeName(ensureStatChange(move, 0).type);
                case COL_STAT1_STAGES:
                    return ensureStatChange(move, 0).stages;
                case COL_STAT1_CHANCE:
                    return (int) Math.round(ensureStatChange(move, 0).percentChance);
                case COL_STAT2_STAT:
                    return getStatChangeName(ensureStatChange(move, 1).type);
                case COL_STAT2_STAGES:
                    return ensureStatChange(move, 1).stages;
                case COL_STAT2_CHANCE:
                    return (int) Math.round(ensureStatChange(move, 1).percentChance);
                case COL_STAT3_STAT:
                    return getStatChangeName(ensureStatChange(move, 2).type);
                case COL_STAT3_STAGES:
                    return ensureStatChange(move, 2).stages;
                case COL_STAT3_CHANCE:
                    return (int) Math.round(ensureStatChange(move, 2).percentChance);
                case COL_MAKES_CONTACT:
                    return move.makesContact;
                case COL_AFFECTED_BY_PROTECT:
                    return move.isProtectedFromProtect;
                case COL_REFLECTED_BY_MAGIC_COAT:
                    return move.isMagicCoatAffected;
                case COL_AFFECTED_BY_SNATCH:
                    return move.isSnatchAffected;
                case COL_AFFECTED_BY_MIRROR_MOVE:
                    return move.isMirrorMoveAffected;
                case COL_CHARGE_MOVE:
                    return move.isChargeMove;
                case COL_RECHARGE_MOVE:
                    return move.isRechargeMove;
                case COL_PUNCH_MOVE:
                    return move.isPunchMove;
                case COL_SOUND_MOVE:
                    return move.isSoundMove;
                case COL_GROUNDED_BY_GRAVITY:
                    return move.groundedByGravity;
                case COL_DEFROSTS_USER:
                    return move.defrostsUser;
                case COL_HITS_NON_ADJACENT:
                    return move.hitsNonAdjacent;
                case COL_HEALING_MOVE_FLAG:
                    return move.isHealingMove;
                case COL_BYPASSES_SUBSTITUTE:
                    return move.bypassesSubstitute;
                case COL_EXTRA_FLAG1:
                    return move.extraFlag1;
                case COL_EXTRA_FLAG2:
                    return move.extraFlag2;
                case COL_TRAP_MOVE:
                    return move.isTrapMove;
                case COL_HIDES_HP_BARS:
                    return move.hidesHpBars;
                case COL_REMOVE_SHADOW:
                    return move.removesTargetShadow;
                case COL_CONTEST_EFFECT:
                    return move.contestEffect;
                case COL_CONTEST_TYPE:
                    return move.contestType;
                default:
                    return defaultValueForColumn(col);
            }
        }

        private Object defaultValueForColumn(int col) {
            if (isBooleanColumn(col)) {
                return Boolean.FALSE;
            }
            if (isStringColumn(col)) {
                return "";
            }
            return 0;
        }

        @Override
        public void setValueAt(Object val, int row, int col) {
            if (row + 1 >= movesList.size()) {
                return;
            }
            Move move = movesList.get(row + 1);
            if (move == null) {
                return;
            }
            try {
                switch (col) {
                    case COL_NAME:
                        move.name = stringValue(val);
                        break;
                    case COL_EFFECT:
                        move.effectIndex = clampInt(parseInt(val), 0, 0xFFFF);
                        break;
                    case COL_CATEGORY:
                        move.category = parseCategoryName(stringValue(val));
                        break;
                    case COL_CATEGORY_QUALITY:
                        move.categoryQuality = clampInt(parseCategoryQualityName(stringValue(val)), 0, 255);
                        updateDerivedMoveTypes(move);
                        break;
                    case COL_POWER:
                        move.power = clampInt(parseInt(val), 0, 255);
                        break;
                    case COL_TYPE:
                        if (val != null && !val.toString().isEmpty()) {
                            move.type = Type.valueOf(val.toString());
                        }
                        break;
                    case COL_ACCURACY:
                        move.hitratio = clampInt(parseInt(val), 0, 101);
                        break;
                    case COL_PP:
                        move.pp = clampInt(parseInt(val), 0, 255);
                        break;
                    case COL_TARGET:
                        move.target = clampInt(parseTargetName(stringValue(val)), 0, 255);
                        updateDerivedMoveTypes(move);
                        break;
                    case COL_PRIORITY:
                        move.priority = clampSigned(parseInt(val), -128, 127);
                        break;
                    case COL_MIN_HITS:
                        move.minHits = clampInt(parseInt(val), 0, 15);
                        if (move.maxHits < move.minHits) {
                            move.maxHits = move.minHits;
                        }
                        updateHitCount(move);
                        break;
                    case COL_MAX_HITS:
                        move.maxHits = clampInt(parseInt(val), 0, 15);
                        if (move.maxHits < move.minHits) {
                            move.minHits = move.maxHits;
                        }
                        updateHitCount(move);
                        break;
                    case COL_MIN_TRAP_TURNS:
                        move.minTrapTurns = clampInt(parseInt(val), 0, 255);
                        if (move.minTrapTurns == 0 && move.maxTrapTurns == 0 && !move.isTrapMove) {
                            // allow clearing via Trap Move column
                        } else {
                            move.isTrapMove = move.isTrapMove || move.minTrapTurns > 0 || move.maxTrapTurns > 0;
                        }
                        break;
                    case COL_MAX_TRAP_TURNS:
                        move.maxTrapTurns = clampInt(parseInt(val), 0, 255);
                        if (move.minTrapTurns == 0 && move.maxTrapTurns == 0 && !move.isTrapMove) {
                            // allow clearing via Trap Move column
                        } else {
                            move.isTrapMove = move.isTrapMove || move.minTrapTurns > 0 || move.maxTrapTurns > 0;
                        }
                        break;
                    case COL_STATUS_EFFECT:
                        int statusEffectId = parseStatusEffectName(stringValue(val));
                        move.statusEffect = clampInt(statusEffectId, -1, 0xFFFF);
                        StatusType derivedType = mapStatusTypeFromEffect(move.statusEffect);
                        if (derivedType != null) {
                            move.statusType = derivedType;
                        }
                        if (move.statusEffect == 8) {
                            move.isTrapMove = true;
                        }
                        updateDerivedMoveTypes(move);
                        break;
                    case COL_STATUS_TYPE:
                        StatusType statusType = parseStatusTypeName(stringValue(val));
                        if (statusType == move.statusType) {
                            break; // no-op edit: do not clobber statusEffect ids >= 8 (Taunt, traps, etc.)
                        }
                        move.statusType = statusType;
                        if (statusType == StatusType.TOXIC_POISON) {
                            move.statusEffect = StatusType.POISON.ordinal();
                        } else {
                            move.statusEffect = statusType.ordinal();
                        }
                        updateDerivedMoveTypes(move);
                        break;
                    case COL_STATUS_CHANCE:
                        move.statusPercentChance = clampInt(parseInt(val), 0, 255);
                        move.secondaryEffectChance = (int) move.statusPercentChance;
                        break;
                    case COL_FLINCH_CHANCE:
                        move.flinchPercentChance = clampInt(parseInt(val), 0, 255);
                        break;
                    case COL_CRIT_STAGE:
                        move.criticalStage = clampInt(parseInt(val), 0, 6);
                        if (move.criticalStage >= 6) {
                            move.criticalChance = CriticalChance.GUARANTEED;
                        } else if (move.criticalStage > 0) {
                            move.criticalChance = CriticalChance.INCREASED;
                        } else {
                            move.criticalChance = CriticalChance.NORMAL;
                        }
                        break;
                    case COL_RECOIL:
                        move.recoilPercent = clampSigned(parseInt(val), -128, 127);
                        break;
                    case COL_HEAL:
                        move.absorbPercent = clampInt(parseInt(val), 0, 255);
                        break;
                    case COL_STAT1_STAT:
                        ensureStatChange(move, 0).type = parseStatChangeName(stringValue(val));
                        break;
                    case COL_STAT1_STAGES:
                        ensureStatChange(move, 0).stages = clampSigned(parseInt(val), -12, 12);
                        break;
                    case COL_STAT1_CHANCE:
                        ensureStatChange(move, 0).percentChance = clampInt(parseInt(val), 0, 255);
                        break;
                    case COL_STAT2_STAT:
                        ensureStatChange(move, 1).type = parseStatChangeName(stringValue(val));
                        break;
                    case COL_STAT2_STAGES:
                        ensureStatChange(move, 1).stages = clampSigned(parseInt(val), -12, 12);
                        break;
                    case COL_STAT2_CHANCE:
                        ensureStatChange(move, 1).percentChance = clampInt(parseInt(val), 0, 255);
                        break;
                    case COL_STAT3_STAT:
                        ensureStatChange(move, 2).type = parseStatChangeName(stringValue(val));
                        break;
                    case COL_STAT3_STAGES:
                        ensureStatChange(move, 2).stages = clampSigned(parseInt(val), -12, 12);
                        break;
                    case COL_STAT3_CHANCE:
                        ensureStatChange(move, 2).percentChance = clampInt(parseInt(val), 0, 255);
                        break;
                    case COL_MAKES_CONTACT:
                        move.makesContact = parseBoolean(val);
                        break;
                    case COL_AFFECTED_BY_PROTECT:
                        move.isProtectedFromProtect = parseBoolean(val);
                        break;
                    case COL_REFLECTED_BY_MAGIC_COAT:
                        move.isMagicCoatAffected = parseBoolean(val);
                        break;
                    case COL_AFFECTED_BY_SNATCH:
                        move.isSnatchAffected = parseBoolean(val);
                        break;
                    case COL_AFFECTED_BY_MIRROR_MOVE:
                        move.isMirrorMoveAffected = parseBoolean(val);
                        break;
                    case COL_CHARGE_MOVE:
                        move.isChargeMove = parseBoolean(val);
                        break;
                    case COL_RECHARGE_MOVE:
                        move.isRechargeMove = parseBoolean(val);
                        break;
                    case COL_PUNCH_MOVE:
                        move.isPunchMove = parseBoolean(val);
                        break;
                    case COL_SOUND_MOVE:
                        move.isSoundMove = parseBoolean(val);
                        break;
                    case COL_GROUNDED_BY_GRAVITY:
                        move.groundedByGravity = parseBoolean(val);
                        break;
                    case COL_DEFROSTS_USER:
                        move.defrostsUser = parseBoolean(val);
                        break;
                    case COL_HITS_NON_ADJACENT:
                        move.hitsNonAdjacent = parseBoolean(val);
                        break;
                    case COL_HEALING_MOVE_FLAG:
                        move.isHealingMove = parseBoolean(val);
                        break;
                    case COL_BYPASSES_SUBSTITUTE:
                        move.bypassesSubstitute = parseBoolean(val);
                        break;
                    case COL_EXTRA_FLAG1:
                        move.extraFlag1 = parseBoolean(val);
                        break;
                    case COL_EXTRA_FLAG2:
                        move.extraFlag2 = parseBoolean(val);
                        break;
                    case COL_TRAP_MOVE:
                        move.isTrapMove = parseBoolean(val);
                        break;
                    case COL_HIDES_HP_BARS:
                        move.hidesHpBars = parseBoolean(val);
                        break;
                    case COL_REMOVE_SHADOW:
                        move.removesTargetShadow = parseBoolean(val);
                        break;
                    case COL_CONTEST_EFFECT:
                        move.contestEffect = clampInt(parseInt(val), 0, 255);
                        break;
                    case COL_CONTEST_TYPE:
                        move.contestType = clampInt(parseInt(val), 0, 255);
                        break;
                    default:
                        break;
                }
            } catch (Exception ex) {
                ex.printStackTrace();
            } finally {
                fireTableCellUpdated(row, col);
            }
        }

        private Move.StatChange ensureStatChange(Move move, int index) {
            if (move.statChanges[index] == null) {
                move.statChanges[index] = new Move.StatChange();
                move.statChanges[index].type = StatChangeType.NONE;
            }
            return move.statChanges[index];
        }

        private String getCategoryName(MoveCategory category) {
            if (category == null) {
                return "Status";
            }
            switch (category) {
                case PHYSICAL:
                    return "Physical";
                case SPECIAL:
                    return "Special";
                case STATUS:
                default:
                    return "Status";
            }
        }

        private MoveCategory parseCategoryName(String name) {
            if (name == null) {
                return MoveCategory.STATUS;
            }
            switch (name) {
                case "Physical":
                    return MoveCategory.PHYSICAL;
                case "Special":
                    return MoveCategory.SPECIAL;
                case "Status":
                default:
                    return MoveCategory.STATUS;
            }
        }

        private String getCategoryQualityName(int quality) {
            for (CategoryQualityOption option : CATEGORY_QUALITY_OPTIONS) {
                if (option.id == quality) {
                    return option.label;
                }
            }
            return String.valueOf(quality);
        }

        private int parseCategoryQualityName(String name) {
            if (name == null || name.isEmpty()) {
                return 0;
            }
            for (CategoryQualityOption option : CATEGORY_QUALITY_OPTIONS) {
                if (option.label.equals(name)) {
                    return option.id;
                }
            }
            try {
                return Integer.parseInt(name);
            } catch (NumberFormatException ex) {
                return 0;
            }
        }

        private String getTargetName(int target) {
            int normalized = target & 0xFF;
            for (TargetOption option : MOVE_TARGET_OPTIONS) {
                if (option.value == normalized) {
                    return option.label;
                }
            }
            return String.format("0x%02X", normalized);
        }

        private int parseTargetName(String targetName) {
            if (targetName == null) {
                return MOVE_TARGET_OPTIONS[0].value;
            }
            String trimmed = targetName.trim();
            if (trimmed.isEmpty()) {
                return MOVE_TARGET_OPTIONS[0].value;
            }
            for (TargetOption option : MOVE_TARGET_OPTIONS) {
                if (option.label.equalsIgnoreCase(trimmed)) {
                    return option.value;
                }
            }
            Integer legacyValue = LEGACY_TARGET_ALIASES.get(trimmed);
            if (legacyValue == null) {
                legacyValue = LEGACY_TARGET_ALIASES.get(trimmed.toLowerCase(Locale.ROOT));
            }
            if (legacyValue != null) {
                return legacyValue;
            }
            if (trimmed.startsWith("0x") || trimmed.startsWith("0X")) {
                try {
                    return Integer.parseInt(trimmed.substring(2), 16) & 0xFF;
                } catch (NumberFormatException ignored) {
                }
            }
            try {
                return Integer.parseInt(trimmed) & 0xFF;
            } catch (NumberFormatException ignored) {
                // fall through
            }
            return MOVE_TARGET_OPTIONS[0].value;
        }

        private String getStatusTypeName(StatusType type) {
            if (type == null) {
                return "None";
            }
            switch (type) {
                case PARALYZE:
                    return "Paralyze";
                case SLEEP:
                    return "Sleep";
                case FREEZE:
                    return "Freeze";
                case BURN:
                    return "Burn";
                case POISON:
                    return "Poison";
                case CONFUSION:
                    return "Confusion";
                case TOXIC_POISON:
                    return "Toxic Poison";
                case NONE:
                default:
                    return "None";
            }
        }

        private String getStatusEffectName(int id) {
            for (StatusEffectOption option : STATUS_EFFECT_OPTIONS) {
                if (option.id == id) {
                    return option.label;
                }
            }
            return String.valueOf(id);
        }

        private int parseStatusEffectName(String name) {
            if (name == null || name.isEmpty()) {
                return 0;
            }
            for (StatusEffectOption option : STATUS_EFFECT_OPTIONS) {
                if (option.label.equals(name)) {
                    return option.id;
                }
            }
            try {
                return Integer.parseInt(name);
            } catch (NumberFormatException ex) {
                return 0;
            }
        }

        private StatusType parseStatusTypeName(String name) {
            if (name == null || name.isEmpty() || name.equals("None")) {
                return StatusType.NONE;
            }
            switch (name) {
                case "Paralyze":
                    return StatusType.PARALYZE;
                case "Sleep":
                    return StatusType.SLEEP;
                case "Freeze":
                    return StatusType.FREEZE;
                case "Burn":
                    return StatusType.BURN;
                case "Poison":
                    return StatusType.POISON;
                case "Confusion":
                    return StatusType.CONFUSION;
                case "Toxic Poison":
                    return StatusType.TOXIC_POISON;
                default:
                    return StatusType.NONE;
            }
        }

        private StatusType mapStatusTypeFromEffect(int effectId) {
            switch (effectId) {
                case 0:
                    return StatusType.NONE;
                case 1:
                    return StatusType.PARALYZE;
                case 2:
                    return StatusType.SLEEP;
                case 3:
                    return StatusType.FREEZE;
                case 4:
                    return StatusType.BURN;
                case 5:
                    return StatusType.POISON;
                case 6:
                    return StatusType.CONFUSION;
                default:
                    return null;
            }
        }

        private void updateDerivedMoveTypes(Move move) {
            switch (move.categoryQuality) {
                case Gen5Constants.noDamageStatChangeQuality:
                case Gen5Constants.noDamageStatusAndStatChangeQuality:
                    if (move.target == 6 || move.target == 7) {
                        move.statChangeMoveType = StatChangeMoveType.NO_DAMAGE_USER;
                    } else {
                        move.statChangeMoveType = StatChangeMoveType.NO_DAMAGE_TARGET;
                    }
                    break;
                case Gen5Constants.damageTargetDebuffQuality:
                    move.statChangeMoveType = StatChangeMoveType.DAMAGE_TARGET;
                    break;
                case Gen5Constants.damageUserBuffQuality:
                    move.statChangeMoveType = StatChangeMoveType.DAMAGE_USER;
                    break;
                default:
                    move.statChangeMoveType = StatChangeMoveType.NONE_OR_UNKNOWN;
                    break;
            }

            switch (move.categoryQuality) {
                case Gen5Constants.noDamageStatusQuality:
                case Gen5Constants.noDamageStatusAndStatChangeQuality:
                    move.statusMoveType = StatusMoveType.NO_DAMAGE;
                    break;
                case Gen5Constants.damageStatusQuality:
                    move.statusMoveType = StatusMoveType.DAMAGE;
                    break;
                default:
                    move.statusMoveType = StatusMoveType.NONE_OR_UNKNOWN;
                    break;
            }

            if (move.statusEffect == 0) {
                move.statusMoveType = StatusMoveType.NONE_OR_UNKNOWN;
            }
        }

        private String getStatChangeName(StatChangeType type) {
            if (type == null) {
                return "None";
            }
            switch (type) {
                case ATTACK:
                    return "Attack";
                case DEFENSE:
                    return "Defense";
                case SPECIAL_ATTACK:
                    return "Sp. Atk";
                case SPECIAL_DEFENSE:
                    return "Sp. Def";
                case SPEED:
                    return "Speed";
                case ACCURACY:
                    return "Accuracy";
                case EVASION:
                    return "Evasion";
                case ALL:
                    return "All";
                case SPECIAL:
                    return "Special";
                case NONE:
                default:
                    return "None";
            }
        }

        private StatChangeType parseStatChangeName(String name) {
            if (name == null || name.isEmpty() || name.equals("None")) {
                return StatChangeType.NONE;
            }
            switch (name) {
                case "Attack":
                    return StatChangeType.ATTACK;
                case "Defense":
                    return StatChangeType.DEFENSE;
                case "Sp. Atk":
                    return StatChangeType.SPECIAL_ATTACK;
                case "Sp. Def":
                    return StatChangeType.SPECIAL_DEFENSE;
                case "Speed":
                    return StatChangeType.SPEED;
                case "Accuracy":
                    return StatChangeType.ACCURACY;
                case "Evasion":
                    return StatChangeType.EVASION;
                case "All":
                    return StatChangeType.ALL;
                case "Special":
                    return StatChangeType.SPECIAL;
                default:
                    return StatChangeType.NONE;
            }
        }

        private boolean parseBoolean(Object val) {
            if (val instanceof Boolean) {
                return (Boolean) val;
            }
            if (val != null) {
                String str = val.toString().toLowerCase();
                return str.equals("true") || str.equals("1");
            }
            return false;
        }

        private int parseInt(Object val) {
            if (val instanceof Integer) {
                return (Integer) val;
            }
            try {
                return Integer.parseInt(val.toString());
            } catch (Exception e) {
                return 0;
            }
        }

        private void updateHitCount(Move move) {
            if (move.minHits > 1 || move.maxHits > 1) {
                int minHits = Math.max(1, move.minHits);
                int maxHits = Math.max(minHits, move.maxHits);
                move.hitCount = (minHits + maxHits) / 2.0;
            } else if (GlobalConstants.normalMultihitMoves.contains(move.number)) {
                move.hitCount = 19 / 6.0;
            } else if (GlobalConstants.doubleHitMoves.contains(move.number)) {
                move.hitCount = 2;
            } else if (move.number == MoveIDs.tripleKick) {
                move.hitCount = 2.71;
            } else {
                move.hitCount = 1;
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

        private int clampSigned(int value, int min, int max) {
            if (value < min) {
                return min;
            }
            if (value > max) {
                return max;
            }
            return value;
        }

        private String stringValue(Object val) {
            return val == null ? "" : val.toString();
        }
    }

}

