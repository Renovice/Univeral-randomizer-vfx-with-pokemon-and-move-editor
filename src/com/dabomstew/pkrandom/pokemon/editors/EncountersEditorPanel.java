package com.dabomstew.pkrandom.pokemon.editors;

import com.dabomstew.pkrandom.log.ManualEditRegistry;
import com.dabomstew.pkromio.gamedata.Encounter;
import com.dabomstew.pkromio.gamedata.EncounterArea;
import com.dabomstew.pkromio.gamedata.Species;
import com.dabomstew.pkromio.romhandlers.RomHandler;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableColumn;
import javax.swing.table.TableColumnModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Wild-Encounter editor: pick a wild area on the left, edit its encounter slots
 * (species + level range) on the right. Generation-agnostic — everything is
 * driven through the {@link RomHandler} interface ({@code getEncounters} /
 * {@code setEncounters}), so the single panel is reused by every generation that
 * actually has wild-encounter data.
 *
 * <p>Persistence model (deliberately the safest of the two patterns described in
 * the surrounding panels): the panel fetches the encounter list once and edits
 * only deep copies. The live handler data is never touched until {@link #save()}
 * pushes the working copies via {@code setEncounters(true, ...)} with the same
 * {@code useTimeOfDay} flag used to read them. Closing without saving therefore
 * leaks nothing — there is nothing to revert because the handler was never
 * mutated.</p>
 */
public class EncountersEditorPanel extends JPanel {

    // Use the SAME flag for get and set throughout the panel.
    private static final boolean USE_TIME_OF_DAY = true;

    private static final Font FONT_LABEL = new Font("Segoe UI", Font.PLAIN, 12);
    private static final Font FONT_VALUE = new Font("Segoe UI", Font.BOLD, 12);

    private final RomHandler romHandler;
    private final List<Species> speciesList;       // getSpeciesInclFormes(), index-stable
    private final String[] speciesOptions;         // "<dex>: <name><forme>" for the combo
    private final Map<String, Species> speciesByOption = new LinkedHashMap<String, Species>();

    private List<EncounterArea> working;           // deep copies; only these are edited
    private final boolean showForme;
    private final boolean showSOS;
    // Gen7 stores ONE level range per area (only slot 0's level is written by
    // setEncounters), so a per-slot level edit must be applied to every slot.
    private final boolean areaWideLevels;

    private JList<AreaEntry> areaJList;
    private DefaultListModel<AreaEntry> areaListModel;
    private JTextField searchField;
    private JTable slotTable;
    private SlotTableModel slotModel;
    private JLabel areaTitleLabel;
    private JLabel areaInfoLabel;
    private JSpinner rateSpinner;
    private JLabel rateLabel;
    private boolean adjustingRate;

    private EncounterArea currentArea;
    private Runnable saveAction;

    private final java.util.Set<String> editLog = new LinkedHashSet<String>();

    public EncountersEditorPanel(RomHandler romHandler) {
        this.romHandler = romHandler;
        this.speciesList = romHandler.getSpeciesInclFormes();

        List<EncounterArea> original;
        try {
            original = romHandler.getEncounters(USE_TIME_OF_DAY);
        } catch (Exception e) {
            original = new ArrayList<EncounterArea>();
        }
        this.working = deepCopy(original);

        boolean anyForme = false;
        boolean anySOS = false;
        for (EncounterArea area : working) {
            if (area == null) {
                continue;
            }
            for (Encounter enc : area) {
                if (enc == null) {
                    continue;
                }
                if (enc.getFormeNumber() != 0) {
                    anyForme = true;
                }
                if (enc.isSOS()) {
                    anySOS = true;
                }
            }
        }
        this.showForme = anyForme;
        this.showSOS = anySOS;
        // Gen7 (SM/USUM) writes a single area-wide level range from slot 0 only.
        this.areaWideLevels = romHandler.generationOfPokemon() == 7;

        this.speciesOptions = buildSpeciesOptions();
        initUI();
        if (!areaListModel.isEmpty()) {
            areaJList.setSelectedIndex(0);
        } else {
            showArea(null);
        }
    }

    public void setSaveAction(Runnable saveAction) {
        this.saveAction = saveAction;
    }

    // ------------------------------------------------------------------
    // Setup
    // ------------------------------------------------------------------

    private String[] buildSpeciesOptions() {
        List<String> opts = new ArrayList<String>();
        opts.add("0: (None)");
        for (int i = 0; i < speciesList.size(); i++) {
            Species s = speciesList.get(i);
            if (s == null) {
                continue;
            }
            // Include the forme suffix so options are unique even when several
            // formes share a dex number; resolve selections by this exact string.
            String label = EditorUtils.formatSpeciesDisplayNameWithId(s);
            if (speciesByOption.containsKey(label)) {
                // De-dup collisions defensively (shouldn't happen with suffixes).
                label = label + "  [#" + i + "]";
            }
            speciesByOption.put(label, s);
            opts.add(label);
        }
        return opts.toArray(new String[0]);
    }

    private static List<EncounterArea> deepCopy(List<EncounterArea> src) {
        List<EncounterArea> out = new ArrayList<EncounterArea>(src == null ? 0 : src.size());
        if (src == null) {
            return out;
        }
        for (EncounterArea area : src) {
            out.add(copyArea(area));
        }
        return out;
    }

    private static EncounterArea copyArea(EncounterArea src) {
        if (src == null) {
            return null;
        }
        EncounterArea copy = new EncounterArea();
        for (Encounter enc : src) {
            copy.add(copyEncounter(enc));
        }
        copy.setRate(src.getRate());
        copy.setDisplayName(src.getDisplayName());
        copy.setMapIndex(src.getMapIndex());
        copy.setLocationTag(src.getLocationTag());
        copy.setEncounterType(src.getEncounterType());
        copy.setPostGame(src.isPostGame());
        copy.setPartiallyPostGameCutoff(src.getPartiallyPostGameCutoff());
        copy.setForceMultipleSpecies(src.isForceMultipleSpecies());
        copy.banAllSpecies(src.getBannedSpecies());
        return copy;
    }

    private static Encounter copyEncounter(Encounter src) {
        if (src == null) {
            return null;
        }
        Encounter copy = new Encounter();
        copy.setLevel(src.getLevel());
        copy.setMaxLevel(src.getMaxLevel());
        copy.setSpecies(src.getSpecies());
        copy.setFormeNumber(src.getFormeNumber());
        copy.setSOS(src.isSOS());
        copy.setSosType(src.getSosType());
        return copy;
    }

    private void initUI() {
        setLayout(new BorderLayout());
        setBackground(EditorTheme.surface());

        add(buildToolbar(), BorderLayout.NORTH);
        add(buildSidebar(), BorderLayout.WEST);
        add(buildDetail(), BorderLayout.CENTER);
    }

    private JPanel buildToolbar() {
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 8));
        toolbar.setBackground(EditorTheme.toolbar());
        toolbar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, EditorTheme.border()),
                new EmptyBorder(5, 5, 5, 5)));

        JButton saveButton = EditorUtils.createStyledButton("Save", new Color(76, 175, 80));
        saveButton.addActionListener(e -> {
            if (saveAction != null) {
                saveAction.run();
            } else {
                save();
            }
        });
        toolbar.add(saveButton);
        toolbar.add(Box.createHorizontalStrut(10));

        JLabel infoLabel = new JLabel(String.format("Wild Encounters - %d areas", working.size()));
        infoLabel.setFont(FONT_LABEL);
        infoLabel.setForeground(EditorTheme.mutedText());
        toolbar.add(infoLabel);

        return toolbar;
    }

    private JPanel buildSidebar() {
        JPanel sidebar = new JPanel(new BorderLayout(0, 6));
        sidebar.setPreferredSize(new Dimension(300, 10));
        sidebar.setBackground(EditorTheme.surface());
        sidebar.setBorder(new EmptyBorder(12, 12, 12, 0));

        searchField = new JTextField();
        searchField.setFont(FONT_LABEL);
        searchField.setToolTipText("Filter areas by name");
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { filterList(); }
            public void removeUpdate(DocumentEvent e) { filterList(); }
            public void changedUpdate(DocumentEvent e) { filterList(); }
        });
        JPanel searchRow = new JPanel(new BorderLayout(6, 0));
        searchRow.setBackground(EditorTheme.surface());
        JLabel lbl = new JLabel("Search:");
        lbl.setFont(FONT_LABEL);
        lbl.setForeground(EditorTheme.mutedText());
        searchRow.add(lbl, BorderLayout.WEST);
        searchRow.add(searchField, BorderLayout.CENTER);
        sidebar.add(searchRow, BorderLayout.NORTH);

        areaListModel = new DefaultListModel<AreaEntry>();
        for (int i = 0; i < working.size(); i++) {
            EncounterArea area = working.get(i);
            if (area != null) {
                areaListModel.addElement(new AreaEntry(area, i));
            }
        }
        areaJList = new JList<AreaEntry>(areaListModel);
        areaJList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        areaJList.setBackground(EditorTheme.surface());
        areaJList.setForeground(EditorTheme.text());
        areaJList.setFont(FONT_LABEL);
        areaJList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                AreaEntry sel = areaJList.getSelectedValue();
                if (sel != null && sel.area != currentArea) {
                    showArea(sel.area);
                }
            }
        });
        JScrollPane listScroll = new JScrollPane(areaJList);
        listScroll.setBorder(BorderFactory.createLineBorder(EditorTheme.border()));
        listScroll.getVerticalScrollBar().setUnitIncrement(16);
        sidebar.add(listScroll, BorderLayout.CENTER);

        return sidebar;
    }

    private JPanel buildDetail() {
        JPanel detail = new JPanel(new BorderLayout(0, 8));
        detail.setBackground(EditorTheme.surface());
        detail.setBorder(new EmptyBorder(12, 12, 12, 12));

        // Header: area name + info + (optional) rate spinner
        JPanel header = new JPanel(new BorderLayout(10, 4));
        header.setOpaque(false);

        JPanel titleCol = new JPanel();
        titleCol.setOpaque(false);
        titleCol.setLayout(new BoxLayout(titleCol, BoxLayout.Y_AXIS));
        areaTitleLabel = new JLabel(" ");
        areaTitleLabel.setFont(new Font("Segoe UI", Font.BOLD, 18));
        areaTitleLabel.setForeground(EditorTheme.text());
        areaTitleLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        titleCol.add(areaTitleLabel);
        areaInfoLabel = new JLabel(" ");
        areaInfoLabel.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        areaInfoLabel.setForeground(EditorTheme.mutedText());
        areaInfoLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        titleCol.add(areaInfoLabel);
        header.add(titleCol, BorderLayout.CENTER);

        JPanel rateRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        rateRow.setOpaque(false);
        rateLabel = new JLabel("Encounter rate:");
        rateLabel.setFont(FONT_LABEL);
        rateLabel.setForeground(EditorTheme.mutedText());
        rateRow.add(rateLabel);
        rateSpinner = new JSpinner(new SpinnerNumberModel(0, 0, 255, 1));
        rateSpinner.setFont(FONT_VALUE);
        ((JSpinner.DefaultEditor) rateSpinner.getEditor()).getTextField().setColumns(4);
        rateSpinner.addChangeListener(e -> {
            if (adjustingRate || currentArea == null) {
                return;
            }
            int v = (Integer) rateSpinner.getValue();
            if (v != currentArea.getRate()) {
                currentArea.setRate(v);
                note(currentArea, "rate -> " + v);
            }
        });
        rateRow.add(rateSpinner);
        header.add(rateRow, BorderLayout.EAST);

        detail.add(header, BorderLayout.NORTH);

        slotModel = new SlotTableModel();
        slotTable = new JTable(slotModel);
        TableLayoutDefaults.applyBaseTableSettings(slotTable);
        TableLayoutDefaults.installStripedRenderers(slotTable, false);
        slotTable.setRowHeight(26);
        slotTable.getTableHeader().setReorderingAllowed(false);
        slotTable.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        configureColumns();

        JScrollPane tableScroll = new JScrollPane(slotTable);
        tableScroll.setBorder(BorderFactory.createLineBorder(EditorTheme.border()));
        tableScroll.getViewport().setBackground(EditorTheme.surface());
        detail.add(tableScroll, BorderLayout.CENTER);

        JLabel hint = new JLabel("Edits apply to a working copy; click Save (or File -> Save All) to keep them. "
                + "Max Level 0 means the slot uses a single level (Min Level).");
        hint.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        hint.setForeground(EditorTheme.mutedText());
        detail.add(hint, BorderLayout.SOUTH);

        return detail;
    }

    private void configureColumns() {
        TableColumnModel cm = slotTable.getColumnModel();
        // 0 = #, 1 = Species, 2 = Min, 3 = Max, [4 = Forme], [5 = SOS]
        cm.getColumn(0).setPreferredWidth(40);
        cm.getColumn(0).setMaxWidth(60);

        TableColumn speciesCol = cm.getColumn(1);
        speciesCol.setPreferredWidth(260);
        JComboBox<String> combo = new JComboBox<String>(speciesOptions);
        combo.setFont(FONT_VALUE);
        EditorUtils.installSearchableComboBox(combo);
        speciesCol.setCellEditor(new DefaultCellEditor(combo));

        cm.getColumn(2).setPreferredWidth(90);
        cm.getColumn(2).setMaxWidth(140);
        cm.getColumn(2).setCellEditor(new LevelSpinnerEditor(1, 100));
        cm.getColumn(3).setPreferredWidth(90);
        cm.getColumn(3).setMaxWidth(140);
        cm.getColumn(3).setCellEditor(new LevelSpinnerEditor(0, 100));

        int next = 4;
        if (showForme) {
            cm.getColumn(next).setPreferredWidth(70);
            cm.getColumn(next).setMaxWidth(90);
            next++;
        }
        if (showSOS) {
            cm.getColumn(next).setPreferredWidth(70);
            cm.getColumn(next).setMaxWidth(90);
        }
    }

    // ------------------------------------------------------------------
    // Selection / display
    // ------------------------------------------------------------------

    private void filterList() {
        String q = searchField.getText().trim().toLowerCase();
        AreaEntry selected = areaJList.getSelectedValue();
        areaListModel.clear();
        for (int i = 0; i < working.size(); i++) {
            EncounterArea area = working.get(i);
            if (area == null) {
                continue;
            }
            AreaEntry entry = new AreaEntry(area, i);
            if (q.isEmpty() || entry.toString().toLowerCase().contains(q)) {
                areaListModel.addElement(entry);
            }
        }
        if (selected != null) {
            for (int i = 0; i < areaListModel.size(); i++) {
                if (areaListModel.get(i).area == selected.area) {
                    areaJList.setSelectedIndex(i);
                    return;
                }
            }
        }
        if (!areaListModel.isEmpty()) {
            areaJList.setSelectedIndex(0);
        }
    }

    private void showArea(EncounterArea area) {
        stopEditing();
        currentArea = area;
        slotModel.setArea(area);

        if (area == null) {
            areaTitleLabel.setText("(no area selected)");
            areaInfoLabel.setText(" ");
            rateLabel.setVisible(false);
            rateSpinner.setVisible(false);
            return;
        }

        String name = area.getDisplayName();
        if (name == null || name.trim().isEmpty()) {
            name = "Area " + (indexOfArea(area) + 1);
        }
        areaTitleLabel.setText(name);

        StringBuilder info = new StringBuilder();
        info.append(area.size()).append(area.size() == 1 ? " slot" : " slots");
        if (area.getEncounterType() != null) {
            info.append("  -  ").append(area.getEncounterType().name());
        }
        if (area.getLocationTag() != null && !area.getLocationTag().trim().isEmpty()) {
            info.append("  -  ").append(area.getLocationTag());
        }
        if (area.isPostGame()) {
            info.append("  -  post-game");
        }
        areaInfoLabel.setText(info.toString());

        // Show the rate control only when it carries meaning (non-zero somewhere
        // in the ROM); a flat 0 across an area means the game doesn't use it here.
        boolean rateMeaningful = area.getRate() != 0;
        rateLabel.setVisible(rateMeaningful);
        rateSpinner.setVisible(rateMeaningful);
        if (rateMeaningful) {
            adjustingRate = true;
            rateSpinner.setValue(clamp(area.getRate(), 0, 255));
            adjustingRate = false;
        }
    }

    private int indexOfArea(EncounterArea area) {
        for (int i = 0; i < working.size(); i++) {
            if (working.get(i) == area) {
                return i;
            }
        }
        return -1;
    }

    private void stopEditing() {
        if (slotTable != null && slotTable.isEditing()) {
            slotTable.getCellEditor().stopCellEditing();
        }
    }

    // ------------------------------------------------------------------
    // Save / persistence
    // ------------------------------------------------------------------

    public void save() {
        stopEditing();
        boolean ok = true;
        try {
            romHandler.setEncounters(USE_TIME_OF_DAY, working);
        } catch (Exception e) {
            e.printStackTrace();
            ok = false;
            JOptionPane.showMessageDialog(this,
                    "Failed to save wild encounters:\n" + e.getMessage(),
                    "Save Failed",
                    JOptionPane.ERROR_MESSAGE);
        }
        if (!ok) {
            return;
        }
        if (!editLog.isEmpty()) {
            ManualEditRegistry.getInstance().addEntries("Wild Encounters", new ArrayList<String>(editLog));
            editLog.clear();
        }
        if (!EditorUtils.suppressSaveDialogs) {
            JOptionPane.showMessageDialog(this,
                    "Wild encounters updated!\n\nChanges will be saved when you save/randomize the ROM.",
                    "Save Complete",
                    JOptionPane.INFORMATION_MESSAGE);
        }
    }

    /**
     * Nothing to undo on close: the panel only ever mutates its private deep
     * copies, and the handler is touched solely in {@link #save()}. Provided for
     * symmetry with the other editor panels (the frames call it unconditionally).
     */
    public void onWindowClosing() {
        stopEditing();
    }

    private void note(EncounterArea area, String what) {
        String name = area.getDisplayName();
        if (name == null || name.trim().isEmpty()) {
            name = "Area " + (indexOfArea(area) + 1);
        }
        editLog.add(name + ": " + what);
    }

    private Species speciesForOption(String option) {
        if (option == null) {
            return null;
        }
        return speciesByOption.get(option); // null for "0: (None)" or unknown
    }

    private String optionForSpecies(Species s) {
        if (s == null) {
            return "0: (None)";
        }
        String label = EditorUtils.formatSpeciesDisplayNameWithId(s);
        if (speciesByOption.get(label) == s) {
            return label;
        }
        // Fall back to a scan in the rare case of a suffix collision rename.
        for (Map.Entry<String, Species> e : speciesByOption.entrySet()) {
            if (e.getValue() == s) {
                return e.getKey();
            }
        }
        return label;
    }

    private static int clamp(int v, int min, int max) {
        return v < min ? min : (v > max ? max : v);
    }

    // ------------------------------------------------------------------
    // Models / small components
    // ------------------------------------------------------------------

    /** Sidebar list entry pairing an area with its original index (for the fallback name). */
    private static final class AreaEntry {
        final EncounterArea area;
        final int index;

        AreaEntry(EncounterArea area, int index) {
            this.area = area;
            this.index = index;
        }

        @Override
        public String toString() {
            String name = area.getDisplayName();
            if (name == null || name.trim().isEmpty()) {
                name = "Area " + (index + 1);
            }
            return "#" + (index + 1) + "  " + name;
        }
    }

    private final class SlotTableModel extends AbstractTableModel {
        private EncounterArea area;
        private final List<String> columns = new ArrayList<String>();
        private int colSpecies;
        private int colMin;
        private int colMax;
        private int colForme = -1;
        private int colSOS = -1;

        SlotTableModel() {
            columns.add("#");
            columns.add("Species");
            colSpecies = 1;
            columns.add("Min Lv");
            colMin = 2;
            columns.add("Max Lv");
            colMax = 3;
            if (showForme) {
                colForme = columns.size();
                columns.add("Forme");
            }
            if (showSOS) {
                colSOS = columns.size();
                columns.add("SOS");
            }
        }

        void setArea(EncounterArea area) {
            this.area = area;
            fireTableDataChanged();
        }

        @Override
        public int getRowCount() {
            return area == null ? 0 : area.size();
        }

        @Override
        public int getColumnCount() {
            return columns.size();
        }

        @Override
        public String getColumnName(int column) {
            return columns.get(column);
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            if (columnIndex == 0 || columnIndex == colMin || columnIndex == colMax) {
                return Integer.class;
            }
            return String.class;
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            // Editable: species, min, max. Read-only: #, forme, SOS.
            return columnIndex == colSpecies || columnIndex == colMin || columnIndex == colMax;
        }

        private Encounter encAt(int row) {
            if (area == null || row < 0 || row >= area.size()) {
                return null;
            }
            return area.get(row);
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            Encounter enc = encAt(rowIndex);
            if (columnIndex == 0) {
                return rowIndex + 1;
            }
            if (enc == null) {
                return columnIndex == colMin || columnIndex == colMax ? Integer.valueOf(0) : "";
            }
            if (columnIndex == colSpecies) {
                return optionForSpecies(enc.getSpecies());
            }
            if (columnIndex == colMin) {
                return enc.getLevel();
            }
            if (columnIndex == colMax) {
                return enc.getMaxLevel();
            }
            if (columnIndex == colForme) {
                return enc.getFormeNumber() == 0 ? "" : String.valueOf(enc.getFormeNumber());
            }
            if (columnIndex == colSOS) {
                if (!enc.isSOS()) {
                    return "";
                }
                return enc.getSosType() != null ? enc.getSosType().name() : "yes";
            }
            return "";
        }

        @Override
        public void setValueAt(Object aValue, int rowIndex, int columnIndex) {
            Encounter enc = encAt(rowIndex);
            if (enc == null) {
                return;
            }
            if (columnIndex == colSpecies) {
                Species picked = speciesForOption(aValue == null ? null : aValue.toString());
                // Tolerate a null pick (e.g. "(None)"): leave the existing species
                // untouched rather than nulling a slot the game expects to be filled.
                if (picked != null && picked != enc.getSpecies()) {
                    // Mirror WildEncounterRandomizer.setFormeForEncounter: a picked
                    // forme must be stored as (base species + forme number), or the
                    // forme is dropped/corrupted on Gen6/7 (which encode the forme
                    // separately from the base dex number).
                    enc.setFormeNumber(picked.getFormeNumber());
                    Species base = picked;
                    while (!base.isBaseForme()) {
                        base = base.getBaseForme();
                    }
                    enc.setSpecies(base);
                    note(area, "slot " + (rowIndex + 1) + " species -> " + picked.getName());
                    fireTableRowsUpdated(rowIndex, rowIndex);
                }
                return;
            }
            if (columnIndex == colMin) {
                int v = clamp(parseInt(aValue, enc.getLevel()), 1, 100);
                if (v != enc.getLevel()) {
                    // Gen7 persists ONE level range per area (slot 0 only), so a
                    // per-slot min edit must be mirrored across every slot, or it
                    // is silently lost on save. Other gens keep per-slot levels.
                    for (Encounter target : levelEditTargets(enc)) {
                        target.setLevel(v);
                        // Keep a real range valid; maxLevel==0 stays "single level".
                        if (target.getMaxLevel() != 0 && target.getMaxLevel() < v) {
                            target.setMaxLevel(v);
                        }
                    }
                    note(area, (areaWideLevels ? "area" : "slot " + (rowIndex + 1)) + " min level -> " + v);
                    refreshAfterLevelEdit(rowIndex);
                }
                return;
            }
            if (columnIndex == colMax) {
                int v = clamp(parseInt(aValue, enc.getMaxLevel()), 0, 100);
                // 0 is allowed and means "single level" (== Min). A non-zero max
                // below min would invert the range, so floor it at min.
                if (v != 0 && v < enc.getLevel()) {
                    v = enc.getLevel();
                }
                if (v != enc.getMaxLevel()) {
                    // Gen7: one area-wide range (see colMin) -> apply to all slots.
                    for (Encounter target : levelEditTargets(enc)) {
                        int tv = v;
                        if (tv != 0 && tv < target.getLevel()) {
                            tv = target.getLevel();
                        }
                        target.setMaxLevel(tv);
                    }
                    note(area, (areaWideLevels ? "area" : "slot " + (rowIndex + 1)) + " max level -> " + v);
                    refreshAfterLevelEdit(rowIndex);
                }
            }
        }

        /**
         * The slots a level edit should touch: just the edited slot normally, or
         * the whole area on Gen7 (which writes a single area-wide level range).
         */
        private List<Encounter> levelEditTargets(Encounter edited) {
            if (areaWideLevels && area != null) {
                return new ArrayList<Encounter>(area);
            }
            List<Encounter> single = new ArrayList<Encounter>(1);
            single.add(edited);
            return single;
        }

        /** Refresh just the edited row, or the whole table when the edit was area-wide. */
        private void refreshAfterLevelEdit(int rowIndex) {
            if (areaWideLevels) {
                fireTableDataChanged();
            } else {
                fireTableRowsUpdated(rowIndex, rowIndex);
            }
        }

        private int parseInt(Object value, int fallback) {
            if (value instanceof Number) {
                return ((Number) value).intValue();
            }
            if (value == null) {
                return fallback;
            }
            try {
                return Integer.parseInt(value.toString().trim());
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
    }

    /** Integer spinner editor for the level columns, clamped to [min, max]. */
    private static final class LevelSpinnerEditor extends AbstractCellEditor implements javax.swing.table.TableCellEditor {
        private final JSpinner spinner;
        private final int min;
        private final int max;

        LevelSpinnerEditor(int min, int max) {
            this.min = min;
            this.max = max;
            this.spinner = new JSpinner(new SpinnerNumberModel(min, min, max, 1));
            spinner.setFont(FONT_VALUE);
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
                    current = Integer.parseInt(value.toString().trim());
                } catch (NumberFormatException ignored) {
                }
            }
            spinner.setValue(clamp(current, min, max));
            return spinner;
        }
    }
}
