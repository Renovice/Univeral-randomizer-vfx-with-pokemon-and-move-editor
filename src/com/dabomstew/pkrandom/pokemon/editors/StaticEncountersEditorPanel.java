package com.dabomstew.pkrandom.pokemon.editors;

import com.dabomstew.pkrandom.log.ManualEditRegistry;
import com.dabomstew.pkromio.gamedata.Item;
import com.dabomstew.pkromio.gamedata.Species;
import com.dabomstew.pkromio.gamedata.StaticEncounter;
import com.dabomstew.pkromio.romhandlers.RomHandler;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Static-Encounter editor: pick a fixed encounter (legendary / gift / static
 * Pokemon) on the left, edit its core properties on the right. Generation-agnostic
 * — everything is driven through the {@link RomHandler} interface
 * ({@code getStaticPokemon} / {@code setStaticPokemon}), so the single panel is
 * reused by every generation that actually supports static-Pokemon changes.
 *
 * <p>Persistence model (mirrors {@link EncountersEditorPanel}, the safest of the
 * surrounding patterns): the panel fetches the static-encounter list once and
 * edits only deep copies (via the {@link StaticEncounter} copy constructor). The
 * live handler data is never touched until {@link #save()} pushes the working
 * copies via {@code setStaticPokemon(...)}. Closing without saving therefore leaks
 * nothing — there is nothing to revert because the handler was never mutated.</p>
 *
 * <p>Linked encounters (the same logical Pokemon split into several internal
 * battles, e.g. Reshiram/Zekrom in BW1) are intentionally read-only here: editing
 * them is risky and the copy constructor preserves them verbatim. The detail form
 * only surfaces a "(+N linked battles)" note when present.</p>
 */
public class StaticEncountersEditorPanel extends JPanel {

    private static final Font FONT_LABEL = new Font("Segoe UI", Font.PLAIN, 12);
    private static final Font FONT_VALUE = new Font("Segoe UI", Font.BOLD, 12);

    private final RomHandler romHandler;
    private final List<Species> speciesList;       // getSpeciesInclFormes(), index-stable
    private final List<Item> itemList;             // getItems(), id-keyed identity
    private final String[] speciesOptions;         // "<dex>: <name><forme>" for the combo
    private final Map<String, Species> speciesByOption = new LinkedHashMap<String, Species>();
    private final String[] itemOptions;            // "<id>: <name>" (or "None") for the combo

    private List<StaticEncounter> working;         // deep copies; only these are edited

    private JList<StaticEntry> encounterJList;
    private DefaultListModel<StaticEntry> encounterListModel;
    private JTextField searchField;

    private JLabel titleLabel;
    private JLabel infoLabel;
    private JPanel formPanel;
    private JComboBox<String> speciesCombo;
    private JSpinner levelSpinner;
    private JSpinner maxLevelSpinner;
    private JComboBox<String> heldItemCombo;
    private JCheckBox eggCheck;
    private JCheckBox resetMovesCheck;
    private JLabel linkedNote;

    private StaticEncounter currentEncounter;
    private boolean building;                       // suppress listener writes while (re)building the form

    private Runnable saveAction;

    private final java.util.Set<String> editLog = new LinkedHashSet<String>();

    public StaticEncountersEditorPanel(RomHandler romHandler) {
        this.romHandler = romHandler;
        this.speciesList = romHandler.getSpeciesInclFormes();
        this.itemList = romHandler.getItems();

        List<StaticEncounter> original;
        try {
            original = romHandler.getStaticPokemon();
        } catch (Exception e) {
            original = new ArrayList<StaticEncounter>();
        }
        this.working = deepCopy(original);

        this.speciesOptions = buildSpeciesOptions();
        this.itemOptions = buildItemOptions();
        initUI();
        if (!encounterListModel.isEmpty()) {
            encounterJList.setSelectedIndex(0);
        } else {
            showEncounter(null);
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

    private String[] buildItemOptions() {
        List<String> it = new ArrayList<String>();
        it.add("None");
        if (itemList != null) {
            for (Item item : itemList) {
                if (item != null) {
                    // Disambiguate by id (finding #26): multiple item ids can share a
                    // display name, so resolving by bare name would pick the wrong id.
                    it.add(item.getId() + ": " + item.getName());
                }
            }
        }
        return it.toArray(new String[0]);
    }

    private static List<StaticEncounter> deepCopy(List<StaticEncounter> src) {
        List<StaticEncounter> out = new ArrayList<StaticEncounter>(src == null ? 0 : src.size());
        if (src == null) {
            return out;
        }
        for (StaticEncounter se : src) {
            // The copy constructor deep-copies forme, restrictedList, linkedEncounters, etc.
            out.add(se == null ? null : new StaticEncounter(se));
        }
        return out;
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

        JLabel info = new JLabel(String.format("Static Encounters - %d entries", working.size()));
        info.setFont(FONT_LABEL);
        info.setForeground(EditorTheme.mutedText());
        toolbar.add(info);

        return toolbar;
    }

    private JPanel buildSidebar() {
        JPanel sidebar = new JPanel(new BorderLayout(0, 6));
        sidebar.setPreferredSize(new Dimension(300, 10));
        sidebar.setBackground(EditorTheme.surface());
        sidebar.setBorder(new EmptyBorder(12, 12, 12, 0));

        searchField = new JTextField();
        searchField.setFont(FONT_LABEL);
        searchField.setToolTipText("Filter encounters by species");
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

        encounterListModel = new DefaultListModel<StaticEntry>();
        for (int i = 0; i < working.size(); i++) {
            StaticEncounter se = working.get(i);
            if (se != null) {
                encounterListModel.addElement(new StaticEntry(se, i));
            }
        }
        encounterJList = new JList<StaticEntry>(encounterListModel);
        encounterJList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        encounterJList.setBackground(EditorTheme.surface());
        encounterJList.setForeground(EditorTheme.text());
        encounterJList.setFont(FONT_LABEL);
        encounterJList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                StaticEntry sel = encounterJList.getSelectedValue();
                if (sel != null && sel.encounter != currentEncounter) {
                    showEncounter(sel.encounter);
                }
            }
        });
        JScrollPane listScroll = new JScrollPane(encounterJList);
        listScroll.setBorder(BorderFactory.createLineBorder(EditorTheme.border()));
        listScroll.getVerticalScrollBar().setUnitIncrement(16);
        sidebar.add(listScroll, BorderLayout.CENTER);

        return sidebar;
    }

    private JPanel buildDetail() {
        JPanel detail = new JPanel(new BorderLayout(0, 8));
        detail.setBackground(EditorTheme.surface());
        detail.setBorder(new EmptyBorder(12, 12, 12, 12));

        // Header: species name + info line
        JPanel titleCol = new JPanel();
        titleCol.setOpaque(false);
        titleCol.setLayout(new BoxLayout(titleCol, BoxLayout.Y_AXIS));
        titleLabel = new JLabel(" ");
        titleLabel.setFont(new Font("Segoe UI", Font.BOLD, 18));
        titleLabel.setForeground(EditorTheme.text());
        titleLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        titleCol.add(titleLabel);
        infoLabel = new JLabel(" ");
        infoLabel.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        infoLabel.setForeground(EditorTheme.mutedText());
        infoLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        titleCol.add(infoLabel);
        detail.add(titleCol, BorderLayout.NORTH);

        // Detail form (GridBagLayout rows). Controls are created once and reused;
        // showEncounter() repopulates them under the 'building' guard.
        formPanel = new JPanel(new GridBagLayout());
        formPanel.setOpaque(false);
        formPanel.setBorder(new EmptyBorder(8, 0, 0, 0));
        int[] row = { 0 };

        speciesCombo = new JComboBox<String>(speciesOptions);
        speciesCombo.setFont(FONT_VALUE);
        speciesCombo.setForeground(EditorTheme.text());
        speciesCombo.setBackground(EditorTheme.surface());
        EditorUtils.installSearchableComboBox(speciesCombo);
        speciesCombo.addActionListener(e -> {
            if (building || currentEncounter == null) {
                return;
            }
            Species picked = speciesForOption((String) speciesCombo.getSelectedItem());
            // Tolerate a null pick ("(None)"): leave the existing species untouched
            // rather than nulling an encounter the game expects to be filled.
            if (picked != null && picked != currentEncounter.getSpecies()) {
                // Mirror StaticPokemonRandomizer.setSpeciesAndFormeForStaticEncounter:
                // the StaticEncounter stores a base species plus a forme number, so on a
                // species change we must update the forme too. Otherwise the original
                // forme byte persists (e.g. Giratina-Origin -> Mewtwo would keep forme 1,
                // and a forme-0 slot set to Deoxys-Attack would drop the forme), which is
                // Gen6/7 data corruption.
                currentEncounter.setForme(picked.getFormeNumber());
                Species base = picked;
                while (!base.isBaseForme()) {
                    base = base.getBaseForme();
                }
                currentEncounter.setSpecies(base);
                note(currentEncounter, "species -> " + picked.getName());
                // Refresh the read-only forme display in showEncounter()'s info line.
                StringBuilder info = new StringBuilder();
                info.append("#").append(indexOfEncounter(currentEncounter) + 1);
                if (currentEncounter.getForme() != 0) {
                    info.append("  -  forme ").append(currentEncounter.getForme());
                }
                infoLabel.setText(info.toString());
                refreshCurrentRow();
            }
        });
        addRow(formPanel, row, "Species", speciesCombo);

        levelSpinner = new JSpinner(new SpinnerNumberModel(1, 1, 100, 1));
        levelSpinner.setFont(FONT_VALUE);
        ((JSpinner.DefaultEditor) levelSpinner.getEditor()).getTextField().setColumns(4);
        levelSpinner.addChangeListener(e -> {
            if (building || currentEncounter == null) {
                return;
            }
            int v = clamp((Integer) levelSpinner.getValue(), 1, 100);
            if (v != currentEncounter.getLevel()) {
                currentEncounter.setLevel(v);
                // Keep a real range valid; maxLevel==0 stays "single level".
                if (currentEncounter.getMaxLevel() != 0 && currentEncounter.getMaxLevel() < v) {
                    currentEncounter.setMaxLevel(v);
                    building = true;
                    maxLevelSpinner.setValue(v);
                    building = false;
                }
                note(currentEncounter, "level -> " + v);
                refreshCurrentRow();
            }
        });
        addRow(formPanel, row, "Level", levelSpinner);

        maxLevelSpinner = new JSpinner(new SpinnerNumberModel(0, 0, 100, 1));
        maxLevelSpinner.setFont(FONT_VALUE);
        ((JSpinner.DefaultEditor) maxLevelSpinner.getEditor()).getTextField().setColumns(4);
        maxLevelSpinner.addChangeListener(e -> {
            if (building || currentEncounter == null) {
                return;
            }
            int v = clamp((Integer) maxLevelSpinner.getValue(), 0, 100);
            // 0 is allowed and means "single level" (== Level). A non-zero max below
            // the level would invert the range, so floor it at level.
            if (v != 0 && v < currentEncounter.getLevel()) {
                v = currentEncounter.getLevel();
                building = true;
                maxLevelSpinner.setValue(v);
                building = false;
            }
            if (v != currentEncounter.getMaxLevel()) {
                currentEncounter.setMaxLevel(v);
                note(currentEncounter, "max level -> " + v);
                refreshCurrentRow();
            }
        });
        addRow(formPanel, row, "Max Level", maxLevelSpinner);

        heldItemCombo = new JComboBox<String>(itemOptions);
        heldItemCombo.setFont(FONT_VALUE);
        heldItemCombo.setForeground(EditorTheme.text());
        heldItemCombo.setBackground(EditorTheme.surface());
        EditorUtils.installSearchableComboBox(heldItemCombo);
        heldItemCombo.addActionListener(e -> {
            if (building || currentEncounter == null) {
                return;
            }
            Item item = itemForOption((String) heldItemCombo.getSelectedItem());
            if (!sameItem(item, currentEncounter.getHeldItem())) {
                currentEncounter.setHeldItem(item);
                note(currentEncounter, "held item -> " + (item == null ? "(None)" : item.getName()));
            }
        });
        addRow(formPanel, row, "Held Item", heldItemCombo);

        eggCheck = new JCheckBox("Is Egg");
        styleCheck(eggCheck);
        eggCheck.addActionListener(e -> {
            if (building || currentEncounter == null) {
                return;
            }
            boolean v = eggCheck.isSelected();
            if (v != currentEncounter.isEgg()) {
                currentEncounter.setEgg(v);
                note(currentEncounter, "egg -> " + v);
                refreshCurrentRow();
            }
        });
        addRow(formPanel, row, "", eggCheck);

        resetMovesCheck = new JCheckBox("Reset Moves");
        styleCheck(resetMovesCheck);
        resetMovesCheck.addActionListener(e -> {
            if (building || currentEncounter == null) {
                return;
            }
            boolean v = resetMovesCheck.isSelected();
            if (v != currentEncounter.isResetMoves()) {
                currentEncounter.setResetMoves(v);
                note(currentEncounter, "reset moves -> " + v);
            }
        });
        addRow(formPanel, row, "", resetMovesCheck);

        linkedNote = new JLabel(" ");
        linkedNote.setFont(new Font("Segoe UI", Font.ITALIC, 11));
        linkedNote.setForeground(EditorTheme.mutedText());
        addRow(formPanel, row, "", linkedNote);

        // Keep the form pinned to the top-left (don't stretch controls vertically).
        JPanel formHolder = new JPanel(new BorderLayout());
        formHolder.setOpaque(false);
        formHolder.add(formPanel, BorderLayout.NORTH);
        detail.add(formHolder, BorderLayout.CENTER);

        JLabel hint = new JLabel("Edits apply to a working copy; click Save (or File -> Save All) to keep them. "
                + "Max Level 0 means a single level (Level).");
        hint.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        hint.setForeground(EditorTheme.mutedText());
        detail.add(hint, BorderLayout.SOUTH);

        return detail;
    }

    private void addRow(JPanel grid, int[] r, String label, JComponent control) {
        GridBagConstraints gc = new GridBagConstraints();
        gc.gridy = r[0];
        gc.anchor = GridBagConstraints.WEST;
        gc.insets = new Insets(3, 0, 3, 16);
        gc.gridx = 0;
        JLabel l = new JLabel(label);
        l.setFont(FONT_LABEL);
        l.setForeground(EditorTheme.mutedText());
        l.setPreferredSize(new Dimension(90, 22));
        grid.add(l, gc);
        gc.gridx = 1;
        gc.weightx = 1.0;
        grid.add(control, gc);
        r[0]++;
    }

    private void styleCheck(JCheckBox c) {
        c.setOpaque(false);
        c.setForeground(EditorTheme.text());
        c.setFont(FONT_VALUE);
        c.setAlignmentX(Component.LEFT_ALIGNMENT);
    }

    // ------------------------------------------------------------------
    // Selection / display
    // ------------------------------------------------------------------

    private void filterList() {
        String q = searchField.getText().trim().toLowerCase();
        StaticEntry selected = encounterJList.getSelectedValue();
        encounterListModel.clear();
        for (int i = 0; i < working.size(); i++) {
            StaticEncounter se = working.get(i);
            if (se == null) {
                continue;
            }
            StaticEntry entry = new StaticEntry(se, i);
            if (q.isEmpty() || entry.toString().toLowerCase().contains(q)) {
                encounterListModel.addElement(entry);
            }
        }
        if (selected != null) {
            for (int i = 0; i < encounterListModel.size(); i++) {
                if (encounterListModel.get(i).encounter == selected.encounter) {
                    encounterJList.setSelectedIndex(i);
                    return;
                }
            }
        }
        if (!encounterListModel.isEmpty()) {
            encounterJList.setSelectedIndex(0);
        }
    }

    private void showEncounter(StaticEncounter se) {
        currentEncounter = se;
        building = true;
        try {
            boolean enabled = se != null;
            speciesCombo.setEnabled(enabled);
            levelSpinner.setEnabled(enabled);
            maxLevelSpinner.setEnabled(enabled);
            heldItemCombo.setEnabled(enabled);
            eggCheck.setEnabled(enabled);
            resetMovesCheck.setEnabled(enabled);

            if (se == null) {
                titleLabel.setText("(no encounter selected)");
                infoLabel.setText(" ");
                speciesCombo.setSelectedItem("0: (None)");
                levelSpinner.setValue(1);
                maxLevelSpinner.setValue(0);
                heldItemCombo.setSelectedItem("None");
                eggCheck.setSelected(false);
                resetMovesCheck.setSelected(false);
                linkedNote.setText(" ");
                return;
            }

            Species sp = se.getSpecies();
            titleLabel.setText(sp == null ? "(no species)" : sp.getFullName());

            StringBuilder info = new StringBuilder();
            info.append("#").append(indexOfEncounter(se) + 1);
            if (se.getForme() != 0) {
                info.append("  -  forme ").append(se.getForme());
            }
            infoLabel.setText(info.toString());

            speciesCombo.setSelectedItem(optionForSpecies(sp));
            levelSpinner.setValue(clamp(se.getLevel(), 1, 100));
            maxLevelSpinner.setValue(clamp(se.getMaxLevel(), 0, 100));
            heldItemCombo.setSelectedItem(optionForItem(se.getHeldItem()));
            eggCheck.setSelected(se.isEgg());
            resetMovesCheck.setSelected(se.isResetMoves());

            int linked = se.getLinkedEncounters() == null ? 0 : se.getLinkedEncounters().size();
            if (linked > 0) {
                linkedNote.setText("(+" + linked + " linked " + (linked == 1 ? "battle" : "battles")
                        + " - preserved, not editable here)");
            } else {
                linkedNote.setText(" ");
            }
        } finally {
            building = false;
        }
    }

    /** Refresh the sidebar label for the current encounter (species/level changed). */
    private void refreshCurrentRow() {
        if (currentEncounter == null) {
            return;
        }
        Species sp = currentEncounter.getSpecies();
        titleLabel.setText(sp == null ? "(no species)" : sp.getFullName());
        int idx = encounterJList.getSelectedIndex();
        if (idx >= 0) {
            encounterListModel.set(idx, new StaticEntry(currentEncounter, indexOfEncounter(currentEncounter)));
            // Re-selecting fires a (non-adjusting) event, but currentEncounter is
            // unchanged so showEncounter() is skipped by the listener's identity check.
            encounterJList.setSelectedIndex(idx);
        }
    }

    private int indexOfEncounter(StaticEncounter se) {
        for (int i = 0; i < working.size(); i++) {
            if (working.get(i) == se) {
                return i;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------
    // Save / persistence
    // ------------------------------------------------------------------

    public void save() {
        boolean ok = true;
        try {
            if (!romHandler.setStaticPokemon(working)) {
                ok = false;
                JOptionPane.showMessageDialog(this,
                        "Failed to save static encounters:\n"
                                + "The game rejected the static Pokemon list (it was not applied).",
                        "Save Failed",
                        JOptionPane.ERROR_MESSAGE);
            }
        } catch (Exception e) {
            e.printStackTrace();
            ok = false;
            JOptionPane.showMessageDialog(this,
                    "Failed to save static encounters:\n" + e.getMessage(),
                    "Save Failed",
                    JOptionPane.ERROR_MESSAGE);
        }
        if (!ok) {
            return;
        }
        if (!editLog.isEmpty()) {
            ManualEditRegistry.getInstance().addEntries("Static Encounters", new ArrayList<String>(editLog));
            editLog.clear();
        }
        if (!EditorUtils.suppressSaveDialogs) {
            JOptionPane.showMessageDialog(this,
                    "Static encounters updated!\n\nChanges will be saved when you save/randomize the ROM.",
                    "Save Complete",
                    JOptionPane.INFORMATION_MESSAGE);
        }
    }

    /**
     * Nothing to undo on close: the panel only ever mutates its private deep copies,
     * and the handler is touched solely in {@link #save()}. Provided for symmetry
     * with the other editor panels (the frames call it unconditionally).
     */
    public void onWindowClosing() {
        // No-op: working data is a deep copy; the handler is untouched until save().
    }

    private void note(StaticEncounter se, String what) {
        String name;
        Species sp = se.getSpecies();
        name = sp == null ? ("Encounter " + (indexOfEncounter(se) + 1)) : sp.getName();
        editLog.add("#" + (indexOfEncounter(se) + 1) + " " + name + ": " + what);
    }

    // ------------------------------------------------------------------
    // Option <-> model resolution (species & items resolved by id, not name)
    // ------------------------------------------------------------------

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

    // Resolve a held-item combo selection back to an Item by its leading id, not its
    // display name. Item identity is id-based and multiple ids can share a name, so
    // matching on name would silently re-assign the first same-named id (finding #26).
    private Item itemForOption(String option) {
        if (option == null || option.equals("None")) {
            return null;
        }
        int id = parseLeadingId(option);
        if (itemList != null) {
            for (Item item : itemList) {
                if (item != null && item.getId() == id) {
                    return item;
                }
            }
        }
        return null;
    }

    private String optionForItem(Item item) {
        if (item == null) {
            return "None";
        }
        return item.getId() + ": " + item.getName();
    }

    private static boolean sameItem(Item a, Item b) {
        if (a == null) {
            return b == null;
        }
        return b != null && a.getId() == b.getId();
    }

    private static int parseLeadingId(String value) {
        if (value == null) {
            return -1;
        }
        String trimmed = value.trim();
        int colon = trimmed.indexOf(':');
        String token = colon >= 0 ? trimmed.substring(0, colon).trim() : trimmed;
        if (token.isEmpty()) {
            return -1;
        }
        try {
            return Integer.parseInt(token);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static int clamp(int v, int min, int max) {
        return v < min ? min : (v > max ? max : v);
    }

    // ------------------------------------------------------------------
    // Models
    // ------------------------------------------------------------------

    /** Sidebar list entry pairing an encounter with its original index. */
    private static final class StaticEntry {
        final StaticEncounter encounter;
        final int index;

        StaticEntry(StaticEncounter encounter, int index) {
            this.encounter = encounter;
            this.index = index;
        }

        @Override
        public String toString() {
            Species sp = encounter.getSpecies();
            String name = sp == null ? "(none)" : sp.getName();
            StringBuilder sb = new StringBuilder();
            sb.append("#").append(index + 1).append("  ").append(name);
            if (encounter.isEgg()) {
                sb.append(" (egg)");
            } else {
                sb.append(" Lv.").append(encounter.getLevel());
                if (encounter.getMaxLevel() > 0) {
                    sb.append("-").append(encounter.getMaxLevel());
                }
            }
            return sb.toString();
        }
    }
}
