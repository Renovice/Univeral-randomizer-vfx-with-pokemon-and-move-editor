package com.dabomstew.pkrandom.pokemon.editors;

import com.dabomstew.pkrandom.log.ManualEditRegistry;
import com.dabomstew.pkromio.gamedata.Item;
import com.dabomstew.pkromio.gamedata.Species;
import com.dabomstew.pkromio.romhandlers.RomHandler;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Starter-Pokemon editor: one row per starter (Species), plus one row per starter
 * held item when the game supports them. Generation-agnostic — everything is driven
 * through the {@link RomHandler} interface ({@code getStarters} / {@code setStarters}
 * and the optional {@code getStarterHeldItems} / {@code setStarterHeldItems}), so the
 * single panel is reused by every generation that has starter data.
 *
 * <p>Persistence model (mirrors {@link StaticEncountersEditorPanel} and
 * {@link EncountersEditorPanel}, the safest of the surrounding patterns): the panel
 * fetches the starter list (and, if supported, the held-item list) once and edits
 * only private copies. The live handler data is never touched until {@link #save()}
 * pushes the working copies via {@code setStarters(...)} / {@code setStarterHeldItems(...)}.
 * Closing without saving therefore leaks nothing — there is nothing to revert because
 * the handler was never mutated.</p>
 *
 * <p>Counts and order are preserved verbatim. {@link #save()} always builds exactly
 * {@code starterCount()} species in their original order (never inserting a null —
 * an empty/unknown combo keeps that slot's original species), and a held-item list
 * the same size as the original {@code getStarterHeldItems()} (which is independent
 * of the starter count: some games share one held item across all starters). The
 * held-item write is best-effort: it is skipped entirely if {@code setStarters}
 * fails, and any setter that rejects the list (or a "None"/null entry) is tolerated
 * without aborting the save.</p>
 */
public class StartersEditorPanel extends JPanel {

    private static final Font FONT_LABEL = new Font("Segoe UI", Font.PLAIN, 12);
    private static final Font FONT_VALUE = new Font("Segoe UI", Font.BOLD, 12);

    private final RomHandler romHandler;
    private final List<Species> speciesList;       // getSpeciesInclFormes(), index-stable
    private final List<Item> itemList;             // getItems(), id-keyed identity (may contain nulls)
    private final String[] speciesOptions;         // "<dex>: <name><forme>" for the combo
    private final Map<String, Species> speciesByOption = new LinkedHashMap<String, Species>();
    private final String[] itemOptions;            // "<id>: <name>" (or "None") for the combo

    private final int starterCount;
    private final boolean heldItemsSupported;

    // Working copies; only these are edited. Originals are kept to back empty combos.
    private final List<Species> workingStarters = new ArrayList<Species>();
    private final List<Species> originalStarters = new ArrayList<Species>();
    private final List<Item> workingHeldItems = new ArrayList<Item>();

    private final List<JComboBox<String>> speciesCombos = new ArrayList<JComboBox<String>>();
    private final List<JComboBox<String>> heldItemCombos = new ArrayList<JComboBox<String>>();

    private boolean building;                       // suppress listener writes while populating the form

    private Runnable saveAction;

    private final java.util.Set<String> editLog = new LinkedHashSet<String>();

    public StartersEditorPanel(RomHandler romHandler) {
        this.romHandler = romHandler;
        this.speciesList = romHandler.getSpeciesInclFormes();
        this.itemList = romHandler.getItems();

        List<Species> starters;
        try {
            starters = romHandler.getStarters();
        } catch (Exception e) {
            starters = new ArrayList<Species>();
        }
        if (starters == null) {
            starters = new ArrayList<Species>();
        }
        // The number of slots to show/save. Prefer starterCount(); fall back to the
        // fetched list size if the handler reports a non-positive count.
        int count;
        try {
            count = romHandler.starterCount();
        } catch (Exception e) {
            count = starters.size();
        }
        if (count <= 0) {
            count = starters.size();
        }
        this.starterCount = count;
        for (int i = 0; i < starterCount; i++) {
            Species s = i < starters.size() ? starters.get(i) : null;
            originalStarters.add(s);
            workingStarters.add(s);
        }

        boolean heldSupported;
        try {
            heldSupported = romHandler.supportsStarterHeldItems();
        } catch (Exception e) {
            heldSupported = false;
        }
        if (heldSupported) {
            List<Item> held;
            try {
                held = romHandler.getStarterHeldItems();
            } catch (Exception e) {
                held = new ArrayList<Item>();
            }
            if (held == null) {
                held = new ArrayList<Item>();
            }
            // Size the held-item editor by the held-item list itself (NOT by the
            // starter count): some games store a single shared held item.
            for (Item it : held) {
                workingHeldItems.add(it);
            }
            // Only surface the held-item section when there is at least one slot.
            heldSupported = !workingHeldItems.isEmpty();
        }
        this.heldItemsSupported = heldSupported;

        this.speciesOptions = buildSpeciesOptions();
        this.itemOptions = buildItemOptions();
        initUI();
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

    private void initUI() {
        setLayout(new BorderLayout());
        setBackground(EditorTheme.surface());

        add(buildToolbar(), BorderLayout.NORTH);
        add(buildDetail(), BorderLayout.CENTER);

        populateForm();
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

        JLabel info = new JLabel(String.format("Starters - %d %s",
                starterCount, starterCount == 1 ? "starter" : "starters"));
        info.setFont(FONT_LABEL);
        info.setForeground(EditorTheme.mutedText());
        toolbar.add(info);

        return toolbar;
    }

    private JPanel buildDetail() {
        JPanel detail = new JPanel(new BorderLayout(0, 8));
        detail.setBackground(EditorTheme.surface());
        detail.setBorder(new EmptyBorder(12, 12, 12, 12));

        // Header
        JLabel titleLabel = new JLabel("Starter Pokemon");
        titleLabel.setFont(new Font("Segoe UI", Font.BOLD, 18));
        titleLabel.setForeground(EditorTheme.text());
        detail.add(titleLabel, BorderLayout.NORTH);

        // Detail form (GridBagLayout rows). Controls are created once; populateForm()
        // fills them under the 'building' guard.
        JPanel formPanel = new JPanel(new GridBagLayout());
        formPanel.setOpaque(false);
        formPanel.setBorder(new EmptyBorder(8, 0, 0, 0));
        int[] row = { 0 };

        for (int i = 0; i < starterCount; i++) {
            final int slot = i;
            JComboBox<String> combo = new JComboBox<String>(speciesOptions);
            combo.setFont(FONT_VALUE);
            combo.setForeground(EditorTheme.text());
            combo.setBackground(EditorTheme.surface());
            EditorUtils.installSearchableComboBox(combo);
            combo.addActionListener(e -> {
                if (building) {
                    return;
                }
                Species picked = speciesForOption((String) combo.getSelectedItem());
                // Tolerate a null pick ("0: (None)"): keep the slot's original species
                // rather than nulling a starter the game expects to be filled.
                Species effective = picked != null ? picked : originalStarters.get(slot);
                if (effective != workingStarters.get(slot)) {
                    workingStarters.set(slot, effective);
                    note("Starter " + (slot + 1) + " -> "
                            + (effective == null ? "(none)" : effective.getName()));
                }
            });
            speciesCombos.add(combo);
            addRow(formPanel, row, "Starter " + (i + 1), combo);
        }

        if (heldItemsSupported) {
            // Separator row before the held-item section.
            addSeparator(formPanel, row);
            boolean single = workingHeldItems.size() == 1;
            for (int i = 0; i < workingHeldItems.size(); i++) {
                final int slot = i;
                JComboBox<String> combo = new JComboBox<String>(itemOptions);
                combo.setFont(FONT_VALUE);
                combo.setForeground(EditorTheme.text());
                combo.setBackground(EditorTheme.surface());
                EditorUtils.installSearchableComboBox(combo);
                combo.addActionListener(e -> {
                    if (building) {
                        return;
                    }
                    Item item = itemForOption((String) combo.getSelectedItem());
                    if (!sameItem(item, workingHeldItems.get(slot))) {
                        workingHeldItems.set(slot, item);
                        note((single ? "Held item" : "Held item " + (slot + 1)) + " -> "
                                + (item == null ? "(None)" : item.getName()));
                    }
                });
                heldItemCombos.add(combo);
                addRow(formPanel, row, single ? "Held Item" : "Held Item " + (i + 1), combo);
            }
        }

        // Keep the form pinned to the top-left (don't stretch controls vertically).
        JPanel formHolder = new JPanel(new BorderLayout());
        formHolder.setOpaque(false);
        formHolder.add(formPanel, BorderLayout.NORTH);
        detail.add(formHolder, BorderLayout.CENTER);

        JLabel hint = new JLabel("Edits apply to a working copy; click Save (or File -> Save All) to keep them.");
        hint.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        hint.setForeground(EditorTheme.mutedText());
        detail.add(hint, BorderLayout.SOUTH);

        return detail;
    }

    private void addRow(JPanel grid, int[] r, String label, JComponent control) {
        GridBagConstraints gc = new GridBagConstraints();
        gc.gridy = r[0];
        gc.anchor = GridBagConstraints.WEST;
        gc.insets = new Insets(4, 0, 4, 16);
        gc.gridx = 0;
        JLabel l = new JLabel(label);
        l.setFont(FONT_LABEL);
        l.setForeground(EditorTheme.mutedText());
        l.setPreferredSize(new Dimension(110, 24));
        grid.add(l, gc);
        gc.gridx = 1;
        gc.weightx = 1.0;
        gc.fill = GridBagConstraints.HORIZONTAL;
        JPanel comboHolder = new JPanel(new BorderLayout());
        comboHolder.setOpaque(false);
        comboHolder.add(control, BorderLayout.CENTER);
        comboHolder.setPreferredSize(new Dimension(320, 26));
        grid.add(comboHolder, gc);
        r[0]++;
    }

    private void addSeparator(JPanel grid, int[] r) {
        GridBagConstraints gc = new GridBagConstraints();
        gc.gridy = r[0];
        gc.gridx = 0;
        gc.gridwidth = 2;
        gc.weightx = 1.0;
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.insets = new Insets(10, 0, 6, 16);
        JSeparator sep = new JSeparator();
        sep.setForeground(EditorTheme.border());
        grid.add(sep, gc);
        r[0]++;
    }

    // ------------------------------------------------------------------
    // Form population
    // ------------------------------------------------------------------

    private void populateForm() {
        building = true;
        try {
            for (int i = 0; i < speciesCombos.size(); i++) {
                speciesCombos.get(i).setSelectedItem(optionForSpecies(workingStarters.get(i)));
            }
            for (int i = 0; i < heldItemCombos.size(); i++) {
                heldItemCombos.get(i).setSelectedItem(optionForItem(workingHeldItems.get(i)));
            }
        } finally {
            building = false;
        }
    }

    // ------------------------------------------------------------------
    // Save / persistence
    // ------------------------------------------------------------------

    public void save() {
        // Build exactly starterCount species, in order, never inserting a null:
        // an empty/unknown slot keeps its original species.
        List<Species> toSave = new ArrayList<Species>(starterCount);
        for (int i = 0; i < starterCount; i++) {
            Species s = workingStarters.get(i);
            if (s == null) {
                s = originalStarters.get(i);
            }
            toSave.add(s);
        }

        boolean ok = true;
        try {
            if (!romHandler.setStarters(toSave)) {
                ok = false;
            }
        } catch (Exception e) {
            e.printStackTrace();
            ok = false;
        }

        if (!ok) {
            // A failure must surface even during Save All, so this error is unguarded.
            JOptionPane.showMessageDialog(this,
                    "Could not update starters for this game.\n\nNo starter changes were applied.",
                    "Save Failed",
                    JOptionPane.ERROR_MESSAGE);
            // Don't attempt the held-item write if the starters didn't take.
            return;
        }

        // Held items also count toward success: if the setter rejects the list (wrong
        // size) or a null/"None" entry (some games NPE on it), that is a save failure
        // and must be reported rather than swallowed into a false "Save Complete".
        if (heldItemsSupported && !heldItemCombos.isEmpty()) {
            List<Item> heldToSave = new ArrayList<Item>(workingHeldItems);
            try {
                romHandler.setStarterHeldItems(heldToSave);
            } catch (Exception e) {
                e.printStackTrace();
                ok = false;
                JOptionPane.showMessageDialog(this,
                        "Failed to save starter held items:\n" + e.getMessage(),
                        "Save Failed",
                        JOptionPane.ERROR_MESSAGE);
            }
        }

        if (!ok) {
            return;
        }

        if (!editLog.isEmpty()) {
            ManualEditRegistry.getInstance().addEntries("Starters", new ArrayList<String>(editLog));
            editLog.clear();
        }
        if (!EditorUtils.suppressSaveDialogs) {
            JOptionPane.showMessageDialog(this,
                    "Starters updated!\n\nChanges will be saved when you save/randomize the ROM.",
                    "Save Complete",
                    JOptionPane.INFORMATION_MESSAGE);
        }
    }

    /**
     * Nothing to undo on close: the panel only ever mutates its private copies, and
     * the handler is touched solely in {@link #save()}. Provided for symmetry with the
     * other editor panels (the frames call it unconditionally).
     */
    public void onWindowClosing() {
        // No-op: working data is a copy; the handler is untouched until save().
    }

    private void note(String what) {
        editLog.add(what);
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
}
