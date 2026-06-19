package com.dabomstew.pkrandom.pokemon.editors;

import com.dabomstew.pkrandom.log.ManualEditRegistry;
import com.dabomstew.pkromio.gamedata.*;
import com.dabomstew.pkromio.romhandlers.RomHandler;

import javax.swing.*;
import javax.swing.border.AbstractBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Per-Pokemon "card" view: shows every value the editors know about and lets
 * the user edit them inline with a clean UI — sliders/number fields for
 * numerics, dropdowns for enumerated values, and clickable tags to add/remove
 * TM/HM, tutor and egg moves, plus editable level-up rows.
 *
 * Persistence mirrors the sheet panels exactly:
 *  - Species base fields are mutated live (the main-window ROM save reads the
 *    live Species objects); they are committed by the editor's "Save All".
 *  - Learnset / egg moves / TM-HM / tutor edits are kept on working copies and
 *    pushed via the matching RomHandler setters in {@link #save()} (called last
 *    in the frame's Save All, so card edits win for the data types it touched).
 */
public class PokemonCardViewPanel extends JPanel {

    // ---- palette (light/dark variants, synced from ThemeManager) ----
    private static Color BG = new Color(0xF4, 0xF5, 0xF7);
    private static Color CARD_BG = Color.WHITE;
    private static Color CARD_BORDER = new Color(0xDD, 0xDF, 0xE3);
    private static Color TEXT_PRIMARY = new Color(0x21, 0x25, 0x29);
    private static Color TEXT_MUTED = new Color(0x6C, 0x75, 0x7D);
    private static Color CHIP_GRAY = new Color(0xE9, 0xEC, 0xEF);
    private static Color BAR_TRACK = new Color(0xEC, 0xEE, 0xF1);
    private static Color FIELD_BG = Color.WHITE;
    private static final Color ACCENT = new Color(0x3B, 0x82, 0xF6);
    private static final Color LEGEND_GOLD = new Color(0xD4, 0xA0, 0x17);
    private static final Color UB_PURPLE = new Color(0x7C, 0x3A, 0xED);
    private static final Color REMOVE_RED = new Color(0xE0, 0x5A, 0x5A);

    /** Switches the palette to match the global light/dark theme. */
    private static void syncPalette() {
        if (com.dabomstew.pkrandom.gui.ThemeManager.isDarkModeApplied()) {
            // Match FlatLaf's (deepened) dark palette: panel #2A2D2F behind cards
            // elevated to the component colour #343739, border #616365, bright text.
            BG = new Color(0x2A, 0x2D, 0x2F);
            CARD_BG = new Color(0x34, 0x37, 0x39);
            CARD_BORDER = new Color(0x61, 0x63, 0x65);
            TEXT_PRIMARY = new Color(0xF0, 0xF0, 0xF0);
            TEXT_MUTED = new Color(0xC0, 0xC0, 0xC0);
            CHIP_GRAY = new Color(0x2A, 0x2D, 0x2F);
            BAR_TRACK = new Color(0x2A, 0x2D, 0x2F);
            FIELD_BG = new Color(0x2A, 0x2D, 0x2F);
        } else {
            BG = new Color(0xF4, 0xF5, 0xF7);
            CARD_BG = Color.WHITE;
            CARD_BORDER = new Color(0xDD, 0xDF, 0xE3);
            TEXT_PRIMARY = new Color(0x21, 0x25, 0x29);
            TEXT_MUTED = new Color(0x6C, 0x75, 0x7D);
            CHIP_GRAY = new Color(0xE9, 0xEC, 0xEF);
            BAR_TRACK = new Color(0xEC, 0xEE, 0xF1);
            FIELD_BG = Color.WHITE;
        }
    }

    private static final Font FONT_TITLE = new Font("Segoe UI", Font.BOLD, 28);
    private static final Font FONT_SECTION = new Font("Segoe UI", Font.BOLD, 16);
    private static final Font FONT_LABEL = new Font("Segoe UI", Font.PLAIN, 14);
    private static final Font FONT_VALUE = new Font("Segoe UI", Font.BOLD, 14);
    private static final Font FONT_CHIP = new Font("Segoe UI", Font.BOLD, 13);
    private static final Font FONT_SMALL = new Font("Segoe UI", Font.PLAIN, 13);

    private static final String[] EGG_GROUP_NAMES = {
            "None", "Monster", "Water 1", "Bug", "Flying", "Field", "Fairy",
            "Grass", "Human-Like", "Water 3", "Mineral", "Amorphous",
            "Water 2", "Ditto", "Dragon", "Undiscovered"
    };

    private final RomHandler romHandler;
    private final PokemonIconCache iconCache;
    private final int generation;

    private List<Species> pokemonList;
    private List<Move> moveList;
    private List<Item> itemList;
    private Map<Integer, List<MoveLearnt>> movesLearnt;
    private Map<Integer, List<Integer>> eggMoves;
    private Map<Species, boolean[]> tmhmCompatibility;
    private List<Integer> tmMoves;
    private List<Integer> hmMoves;
    private Map<Species, boolean[]> tutorCompatibility;
    private List<Integer> tutorMoves;

    // selectable option sources, built once
    private String[] typeOptions;
    private String[] abilityOptions;
    private String[] itemOptions;
    private String[] growthOptions;

    // edit tracking
    private boolean building;
    private boolean learnsetDirty;
    private boolean eggDirty;
    private boolean tmDirty;
    private boolean tutorDirty;
    private final Set<String> editLog = new LinkedHashSet<>();

    // Evolution-line stat scaling (Base Stats section). lineScaleK is the dampening
    // exponent: 0 = absolute (flat delta), 0.5 = square-root, 1 = full proportional.
    private boolean lineScaleEnabled = false;
    private double lineScaleK = 0.5;
    private List<Species> lineScaleFamily;
    // Base stats as loaded from the ROM, per species (6 ints: HP/Atk/Def/SpA/SpD/Spe).
    // The fixed reference for evolution-line scaling (so it never collapses/syncs when a
    // stat is dragged up to the 255 cap and back down) and for the "Reset to default" button.
    private final Map<Species, int[]> originalStats = new java.util.HashMap<Species, int[]>();
    private Runnable saveAction;

    private JTextField searchField;
    private JList<Species> speciesJList;
    private DefaultListModel<Species> listModel;
    private JScrollPane cardScrollPane;
    private JPanel cardHolder;
    private JPanel sidebarPanel;
    private JPanel searchRow;
    private JPanel navPanel;
    private JScrollPane listScroll;
    private Species currentSpecies;
    private JLabel bstValueLabel;

    public PokemonCardViewPanel(RomHandler romHandler) {
        this.romHandler = romHandler;
        this.iconCache = PokemonIconCache.get(romHandler);
        this.generation = romHandler.generationOfPokemon();
        syncPalette();
        fetchData();
        buildOptionSources();
        initializeUI();
        if (!listModel.isEmpty()) {
            speciesJList.setSelectedIndex(0);
        }
        // Re-apply theme + re-render when this tab becomes visible (working
        // copies are kept, so unsaved edits survive a tab switch).
        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentShown(ComponentEvent e) {
                refresh();
            }
        });
    }

    /** Frame wires this to its Save All so the card's "Save Changes" commits everything. */
    public void setSaveAction(Runnable saveAction) {
        this.saveAction = saveAction;
    }

    /** Re-reads all data from the ROM handler (once, at construction).
     *  The move/compat maps come from the shared {@link EditorDataCache} so the
     *  card and the sheet panels edit the SAME instances (live two-way sync). */
    private void fetchData() {
        pokemonList = romHandler.getSpecies();
        moveList = romHandler.getMoves();
        itemList = romHandler.getItems();
        final EditorDataCache cache = EditorDataCache.get(romHandler);
        movesLearnt = safeGet(new DataFetch<Map<Integer, List<MoveLearnt>>>() {
            public Map<Integer, List<MoveLearnt>> fetch() { return cache.getMovesLearnt(); }
        });
        eggMoves = safeGet(new DataFetch<Map<Integer, List<Integer>>>() {
            public Map<Integer, List<Integer>> fetch() { return cache.getEggMoves(); }
        });
        tmhmCompatibility = safeGet(new DataFetch<Map<Species, boolean[]>>() {
            public Map<Species, boolean[]> fetch() { return cache.getTMHMCompatibility(); }
        });
        tmMoves = safeGet(new DataFetch<List<Integer>>() {
            public List<Integer> fetch() { return romHandler.getTMMoves(); }
        });
        hmMoves = safeGet(new DataFetch<List<Integer>>() {
            public List<Integer> fetch() { return romHandler.getHMMoves(); }
        });
        if (romHandler.hasMoveTutors()) {
            tutorMoves = safeGet(new DataFetch<List<Integer>>() {
                public List<Integer> fetch() { return romHandler.getMoveTutorMoves(); }
            });
            tutorCompatibility = safeGet(new DataFetch<Map<Species, boolean[]>>() {
                public Map<Species, boolean[]> fetch() { return cache.getMoveTutorCompatibility(); }
            });
        } else {
            tutorMoves = null;
            tutorCompatibility = null;
        }
        // Snapshot base stats as loaded — the reference for line-scaling and Reset.
        // Use the forme-inclusive list so evolution families that include alt-formes are all
        // captured (otherwise a forme falls through to the live, possibly-scaled value and drifts).
        List<Species> snapList = null;
        try {
            snapList = romHandler.getSpeciesInclFormes();
        } catch (Exception ignored) {
        }
        if (snapList == null) {
            snapList = pokemonList;
        }
        if (snapList != null) {
            for (Species s : snapList) {
                if (s != null && !originalStats.containsKey(s)) {
                    originalStats.put(s, new int[] {
                            s.getHp(), s.getAttack(), s.getDefense(), s.getSpatk(), s.getSpdef(), s.getSpeed() });
                }
            }
        }
    }

    private void buildOptionSources() {
        // Types — respect the loaded gen / fairy mod, like the Personal sheet.
        List<String> types = new ArrayList<String>();
        types.add(""); // blank = no secondary type
        try {
            for (Type t : romHandler.getTypeTable().getTypes()) {
                types.add(t.name());
            }
        } catch (Exception ignored) {
        }
        typeOptions = types.toArray(new String[0]);

        // Abilities — "0" plus "id: name"
        List<String> abilities = new ArrayList<String>();
        abilities.add("0");
        int max;
        try {
            max = romHandler.highestAbilityIndex();
        } catch (Exception e) {
            max = 0;
        }
        if (max <= 0) {
            max = 199;
        }
        for (int i = 1; i <= max; i++) {
            try {
                String n = romHandler.abilityName(i);
                if (n != null && !n.trim().isEmpty()) {
                    abilities.add(i + ": " + n.trim());
                }
            } catch (Exception ignored) {
            }
        }
        abilityOptions = abilities.toArray(new String[0]);

        // Items — "None" plus names
        List<String> items = new ArrayList<String>();
        items.add("None");
        if (itemList != null) {
            for (Item item : itemList) {
                if (item != null) {
                    items.add(item.getName());
                }
            }
        }
        itemOptions = items.toArray(new String[0]);

        // Growth curves
        ExpCurve[] curves = ExpCurve.values();
        growthOptions = new String[curves.length];
        for (int i = 0; i < curves.length; i++) {
            growthOptions[i] = curves[i].name();
        }
    }

    private interface DataFetch<T> {
        T fetch();
    }

    private static <T> T safeGet(DataFetch<T> fetcher) {
        try {
            return fetcher.fetch();
        } catch (Exception e) {
            return null;
        }
    }

    private void initializeUI() {
        setLayout(new BorderLayout());
        setBackground(BG);

        add(buildSidebar(), BorderLayout.WEST);

        cardHolder = new ScrollableCard();
        cardHolder.setLayout(new BoxLayout(cardHolder, BoxLayout.Y_AXIS));
        cardHolder.setBackground(BG);
        cardHolder.setBorder(new EmptyBorder(12, 12, 12, 12));

        cardScrollPane = new JScrollPane(cardHolder);
        cardScrollPane.setBorder(BorderFactory.createEmptyBorder());
        cardScrollPane.getVerticalScrollBar().setUnitIncrement(16);
        cardScrollPane.getViewport().setBackground(BG);
        add(cardScrollPane, BorderLayout.CENTER);
    }

    // ------------------------------------------------------------------
    // Sidebar
    // ------------------------------------------------------------------

    private JPanel buildSidebar() {
        JPanel sidebar = new JPanel(new BorderLayout(0, 6));
        sidebarPanel = sidebar;
        sidebar.setPreferredSize(new Dimension(250, 10));
        sidebar.setBackground(BG);
        sidebar.setBorder(new EmptyBorder(12, 12, 12, 0));

        searchField = new JTextField();
        searchField.setFont(FONT_LABEL);
        searchField.setToolTipText("Search by name or dex number");
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { filterList(); }
            public void removeUpdate(DocumentEvent e) { filterList(); }
            public void changedUpdate(DocumentEvent e) { filterList(); }
        });

        searchRow = new JPanel(new BorderLayout(6, 0));
        searchRow.setBackground(BG);
        JLabel searchIcon = new JLabel("Search:");
        searchIcon.setFont(FONT_LABEL);
        searchIcon.setForeground(TEXT_MUTED);
        searchRow.add(searchIcon, BorderLayout.WEST);
        searchRow.add(searchField, BorderLayout.CENTER);
        sidebar.add(searchRow, BorderLayout.NORTH);

        listModel = new DefaultListModel<Species>();
        for (Species sp : pokemonList) {
            if (sp != null) {
                listModel.addElement(sp);
            }
        }
        speciesJList = new JList<Species>(listModel);
        speciesJList.setCellRenderer(new SpeciesListRenderer());
        speciesJList.setBackground(CARD_BG);
        speciesJList.setForeground(TEXT_PRIMARY);
        speciesJList.setFixedCellHeight(48);
        speciesJList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                Species sel = speciesJList.getSelectedValue();
                if (sel != null && sel != currentSpecies) {
                    showSpecies(sel);
                }
            }
        });
        listScroll = new JScrollPane(speciesJList);
        listScroll.setBorder(BorderFactory.createLineBorder(CARD_BORDER));
        listScroll.getVerticalScrollBar().setUnitIncrement(16);
        sidebar.add(listScroll, BorderLayout.CENTER);

        JPanel nav = new JPanel(new GridLayout(1, 2, 6, 0));
        navPanel = nav;
        nav.setBackground(BG);
        JButton prevButton = new JButton("< Prev");
        JButton nextButton = new JButton("Next >");
        prevButton.setFont(FONT_VALUE);
        nextButton.setFont(FONT_VALUE);
        prevButton.setFocusPainted(false);
        nextButton.setFocusPainted(false);
        prevButton.addActionListener(e -> step(-1));
        nextButton.addActionListener(e -> step(1));
        nav.add(prevButton);
        nav.add(nextButton);
        sidebar.add(nav, BorderLayout.SOUTH);

        return sidebar;
    }

    private void step(int delta) {
        int size = listModel.getSize();
        if (size == 0) {
            return;
        }
        int idx = speciesJList.getSelectedIndex();
        int next = idx < 0 ? 0 : Math.min(size - 1, Math.max(0, idx + delta));
        speciesJList.setSelectedIndex(next);
        speciesJList.ensureIndexIsVisible(next);
    }

    private void filterList() {
        String query = searchField.getText().trim().toLowerCase();
        Species selected = speciesJList.getSelectedValue();
        listModel.clear();
        for (Species sp : pokemonList) {
            if (sp == null) {
                continue;
            }
            if (query.isEmpty()
                    || sp.getName().toLowerCase().contains(query)
                    || String.valueOf(sp.getNumber()).equals(query)) {
                listModel.addElement(sp);
            }
        }
        if (selected != null && listModel.contains(selected)) {
            speciesJList.setSelectedValue(selected, true);
        } else if (!listModel.isEmpty()) {
            speciesJList.setSelectedIndex(0);
        }
    }

    /** Jump to a species (used by evolution links). */
    private void navigateTo(Species target) {
        if (target == null) {
            return;
        }
        if (pokemonList.contains(target)) {
            if (!listModel.contains(target)) {
                searchField.setText("");
            }
            speciesJList.setSelectedValue(target, true);
        } else {
            speciesJList.clearSelection();
            showSpecies(target);
        }
        cardScrollPane.getVerticalScrollBar().setValue(0);
    }

    /** Rebuilds the current card after a structural edit, deferred to avoid
     *  modifying the component tree from inside a child component's event. */
    private void rebuild(Species sp) {
        SwingUtilities.invokeLater(() -> {
            if (currentSpecies == sp) {
                showSpecies(sp, true); // in-place edit: keep the scroll position
            }
        });
    }

    /** Re-applies the theme palette and redraws the current card (keeps edits). */
    public void refresh() {
        syncPalette();
        applyContainerColors();
        if (currentSpecies != null) {
            showSpecies(currentSpecies, true); // keep scroll position on tab re-show / theme refresh
        }
    }

    private void applyContainerColors() {
        setBackground(BG);
        cardHolder.setBackground(BG);
        cardScrollPane.getViewport().setBackground(BG);
        if (sidebarPanel != null) {
            sidebarPanel.setBackground(BG);
        }
        if (searchRow != null) {
            searchRow.setBackground(BG);
        }
        if (navPanel != null) {
            navPanel.setBackground(BG);
        }
        if (listScroll != null) {
            listScroll.setBorder(BorderFactory.createLineBorder(CARD_BORDER));
        }
        if (speciesJList != null) {
            speciesJList.setBackground(CARD_BG);
            speciesJList.setForeground(TEXT_PRIMARY);
            speciesJList.repaint();
        }
    }

    // ------------------------------------------------------------------
    // Card construction
    // ------------------------------------------------------------------

    private void showSpecies(Species sp) {
        showSpecies(sp, false);
    }

    private void showSpecies(Species sp, boolean preserveScroll) {
        final int previousScroll = preserveScroll
                ? cardScrollPane.getVerticalScrollBar().getValue() : 0;
        building = true;
        currentSpecies = sp;
        bstValueLabel = null;
        lineScaleFamily = null;       // recompute the evolution family for the new species
        cardHolder.removeAll();

        cardHolder.add(buildHeaderSection(sp));
        cardHolder.add(Box.createVerticalStrut(10));
        cardHolder.add(buildStatsSection(sp));
        cardHolder.add(Box.createVerticalStrut(10));
        cardHolder.add(buildAbilitiesItemsSection(sp));
        cardHolder.add(Box.createVerticalStrut(10));
        cardHolder.add(buildBreedingSection(sp));
        cardHolder.add(Box.createVerticalStrut(10));
        cardHolder.add(buildMiscSection(sp));
        cardHolder.add(Box.createVerticalStrut(10));
        cardHolder.add(buildEvolutionSection(sp));
        cardHolder.add(Box.createVerticalStrut(10));
        cardHolder.add(buildLearnsetSection(sp));
        cardHolder.add(Box.createVerticalStrut(10));
        cardHolder.add(buildTMSection(sp));
        if (tutorMoves != null && !tutorMoves.isEmpty()) {
            cardHolder.add(Box.createVerticalStrut(10));
            cardHolder.add(buildTutorSection(sp));
        }
        cardHolder.add(Box.createVerticalStrut(10));
        cardHolder.add(buildEggMovesSection(sp));
        cardHolder.add(Box.createVerticalStrut(10));

        JLabel hint = new JLabel("Edits apply live — click \"Save Changes\" (top right) to keep them, "
                + "or they revert when the editor closes.");
        hint.setFont(FONT_SMALL);
        hint.setForeground(TEXT_MUTED);
        hint.setAlignmentX(Component.LEFT_ALIGNMENT);
        cardHolder.add(hint);

        cardHolder.revalidate();
        cardHolder.repaint();
        // Restore scroll after the new layout is applied; for an in-place edit
        // this keeps your place instead of jumping to the top.
        SwingUtilities.invokeLater(() -> cardScrollPane.getVerticalScrollBar().setValue(previousScroll));
        building = false;
    }

    private JPanel buildHeaderSection(Species sp) {
        JPanel section = createSection(null);

        JPanel row = new JPanel(new GridBagLayout());
        row.setOpaque(false);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridy = 0;
        gbc.anchor = GridBagConstraints.WEST;

        JLabel iconLabel = new JLabel();
        ImageIcon icon = iconCache != null ? iconCache.getScaledIcon(sp, 96) : null;
        if (icon != null) {
            iconLabel.setIcon(icon);
        } else {
            iconLabel.setText("?");
            iconLabel.setFont(FONT_TITLE);
            iconLabel.setForeground(TEXT_MUTED);
            iconLabel.setPreferredSize(new Dimension(96, 96));
            iconLabel.setHorizontalAlignment(SwingConstants.CENTER);
        }
        gbc.gridx = 0;
        gbc.insets = new Insets(0, 0, 0, 16);
        row.add(iconLabel, gbc);

        JPanel nameBlock = new JPanel();
        nameBlock.setOpaque(false);
        nameBlock.setLayout(new BoxLayout(nameBlock, BoxLayout.Y_AXIS));

        JLabel dexLabel = new JLabel(String.format("#%03d", sp.getNumber()));
        dexLabel.setFont(new Font("Segoe UI", Font.BOLD, 13));
        dexLabel.setForeground(TEXT_MUTED);
        dexLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        nameBlock.add(dexLabel);

        String displayName = sp.getName();
        if (sp.getFormeSuffix() != null && !sp.getFormeSuffix().isEmpty()) {
            displayName += sp.getFormeSuffix();
        }
        JLabel nameLabel = new JLabel(displayName);
        nameLabel.setFont(FONT_TITLE);
        nameLabel.setForeground(TEXT_PRIMARY);
        nameLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        nameBlock.add(nameLabel);

        // Type dropdowns (editable) + badges
        JPanel typeRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        typeRow.setOpaque(false);

        JComboBox<String> primaryCombo = makeCombo(typeOptions,
                sp.getPrimaryType(false) != null ? sp.getPrimaryType(false).name() : "",
                value -> {
                    if (value == null || value.isEmpty()) {
                        return; // primary type cannot be cleared
                    }
                    sp.setPrimaryType(parseType(value));
                    noteEdit(sp, "primary type");
                    rebuild(sp);
                });
        JComboBox<String> secondaryCombo = makeCombo(typeOptions,
                sp.hasSecondaryType(false) ? sp.getSecondaryType(false).name() : "",
                value -> {
                    sp.setSecondaryType(value == null || value.isEmpty() ? null : parseType(value));
                    noteEdit(sp, "secondary type");
                    rebuild(sp);
                });
        typeRow.add(taggedControl("Type 1", primaryCombo));
        typeRow.add(taggedControl("Type 2", secondaryCombo));

        if (sp.isLegendary()) {
            typeRow.add(chip(sp.isStrongLegendary() ? "Strong Legendary" : "Legendary", LEGEND_GOLD, Color.WHITE));
        }
        if (sp.isUltraBeast()) {
            typeRow.add(chip("Ultra Beast", UB_PURPLE, Color.WHITE));
        }
        typeRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        nameBlock.add(Box.createVerticalStrut(6));
        nameBlock.add(typeRow);

        gbc.gridx = 1;
        gbc.weightx = 1.0;
        gbc.insets = new Insets(0, 0, 0, 0);
        row.add(nameBlock, gbc);

        // Save Changes button (top-right)
        JButton saveButton = new JButton("Save Changes");
        saveButton.setFont(FONT_VALUE);
        saveButton.setFocusPainted(false);
        saveButton.setToolTipText("Commit all edits (same as File → Save All)");
        saveButton.addActionListener(e -> saveChanges());
        gbc.gridx = 2;
        gbc.weightx = 0;
        gbc.anchor = GridBagConstraints.NORTHEAST;
        row.add(saveButton, gbc);

        section.add(row);
        return section;
    }

    private static int statByIndex(Species s, int i) {
        switch (i) {
            case 0: return s.getHp();
            case 1: return s.getAttack();
            case 2: return s.getDefense();
            case 3: return s.getSpatk();
            case 4: return s.getSpdef();
            case 5: return s.getSpeed();
            default: return 0;
        }
    }

    private static void setStatByIndex(Species s, int i, int v) {
        switch (i) {
            case 0: s.setHp(v); break;
            case 1: s.setAttack(v); break;
            case 2: s.setDefense(v); break;
            case 3: s.setSpatk(v); break;
            case 4: s.setSpdef(v); break;
            case 5: s.setSpeed(v); break;
            default: break;
        }
    }

    /** Strength-slider caption for the current dampening exponent. */
    private static String lineScaleLabel(double k) {
        if (k <= 0.02) {
            return "Absolute";
        }
        if (k >= 0.98) {
            return "Full %";
        }
        if (Math.abs(k - 0.5) <= 0.03) {
            return "√ balanced";
        }
        return String.format("k=%.2f", k);
    }

    /** Base stat value of {@code s} as loaded from the ROM (fixed reference for scaling/reset). */
    private int originalStat(Species s, int statIndex) {
        int[] o = originalStats.get(s);
        return (o != null && statIndex >= 0 && statIndex < o.length) ? o[statIndex] : statByIndex(s, statIndex);
    }

    /**
     * Propagates a base-stat edit across the shown species' whole evolution family.
     * Each member m is set to: clamp( origM + round( delta * (origM / origEdited)^k ) ),
     * where orig* are the ORIGINAL (ROM-loaded) values and delta = newVal - origEdited.
     * Scaling from the fixed originals (not the current, possibly-clamped values) makes
     * it fully path-independent: dragging the edited stat up to the 255 cap and back down
     * restores every relative value instead of collapsing the line to one synced number.
     * k is the strength exponent (0 = same +/- for all, 0.5 = √, 1 = same %).
     */
    private void propagateLineScale(Species sp, int statIndex, int newVal) {
        if (lineScaleFamily == null) {
            lineScaleFamily = EditorUtils.evolutionFamily(sp);
        }
        if (lineScaleFamily.size() <= 1) {
            return;
        }
        int origEdited = originalStat(sp, statIndex);
        int delta = newVal - origEdited;
        int affected = 0;
        for (Species m : lineScaleFamily) {
            if (m == sp) {
                continue;
            }
            int origM = originalStat(m, statIndex);
            int scaled;
            if (delta == 0) {
                scaled = 0;
            } else if (origEdited <= 0) {
                scaled = delta;
            } else {
                scaled = (int) Math.round(delta * Math.pow((double) origM / origEdited, lineScaleK));
            }
            int target = clamp(origM + scaled, 1, 255);
            if (target != statByIndex(m, statIndex)) {
                setStatByIndex(m, statIndex, target);
                affected++;
            }
        }
        if (affected > 0) {
            noteEdit(sp, "scaled base stat across " + affected + " evolution(s)");
        }
    }

    /** Restores ONE species' six base stats to their ROM-load values. */
    private void resetOneBaseStats(Species sp) {
        int[] o = originalStats.get(sp);
        if (o == null) {
            return;
        }
        for (int i = 0; i < 6 && i < o.length; i++) {
            setStatByIndex(sp, i, o[i]);
        }
        noteEdit(sp, "reset base stats to default");
    }

    /**
     * Restores base stats to their ROM-load values. When line-scale is enabled the edit
     * path propagates a stat change across the whole evolution family (propagateLineScale
     * mutates every member's live stats), so reverting only the shown species would leave
     * its evolutions stuck with their scaled values. To avoid that silent intra-family
     * desync, reset every family member from originalStats when line-scale is active.
     */
    private void resetBaseStats(Species sp) {
        if (lineScaleEnabled) {
            List<Species> family = lineScaleFamily;
            if (family == null) {
                family = EditorUtils.evolutionFamily(sp);
            }
            for (Species m : family) {
                resetOneBaseStats(m);
            }
        } else {
            resetOneBaseStats(sp);
        }
        showSpecies(sp, true); // refresh the fields, bars and total
    }

    private JPanel buildStatsSection(Species sp) {
        JPanel section = createSection("Base Stats");

        // Gen 1 has a single "Special" stat (no Sp.Atk/Sp.Def split), stored via get/setSpecial().
        final boolean singleSpecial = generation == 1;
        String[] names = singleSpecial
                ? new String[] { "HP", "Attack", "Defense", "Special", "Speed" }
                : new String[] { "HP", "Attack", "Defense", "Sp. Atk", "Sp. Def", "Speed" };
        int[] values = singleSpecial
                ? new int[] { sp.getHp(), sp.getAttack(), sp.getDefense(), sp.getSpecial(), sp.getSpeed() }
                : new int[] { sp.getHp(), sp.getAttack(), sp.getDefense(), sp.getSpatk(), sp.getSpdef(), sp.getSpeed() };
        @SuppressWarnings("unchecked")
        IntConsumer[] setters = singleSpecial
                ? new IntConsumer[] { sp::setHp, sp::setAttack, sp::setDefense, sp::setSpecial, sp::setSpeed }
                : new IntConsumer[] { sp::setHp, sp::setAttack, sp::setDefense, sp::setSpatk, sp::setSpdef, sp::setSpeed };

        JPanel grid = new JPanel(new GridBagLayout());
        grid.setOpaque(false);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(2, 0, 2, 8);

        for (int i = 0; i < names.length; i++) {
            final IntConsumer setter = setters[i];
            final int idx = i;

            gbc.gridy = i;
            gbc.gridx = 0;
            gbc.weightx = 0;
            gbc.fill = GridBagConstraints.NONE;
            gbc.anchor = GridBagConstraints.WEST;
            JLabel name = new JLabel(names[i]);
            name.setFont(FONT_LABEL);
            name.setForeground(TEXT_MUTED);
            name.setPreferredSize(new Dimension(60, 22));
            grid.add(name, gbc);

            final JTextField field = plainIntField(values[i], 3);
            final StatBar bar = new StatBar(values[i]);

            IntConsumer apply = v -> {
                int c = clamp(v, 1, 255);
                int oldVal = statByIndex(sp, idx);
                setter.accept(c);
                field.setText(String.valueOf(c));
                bar.setValueQuiet(c);
                if (bstValueLabel != null) {
                    bstValueLabel.setText(String.valueOf(currentSpecies.getBST()));
                }
                noteEdit(sp, "base stats");
                if (lineScaleEnabled && c != oldVal && !singleSpecial) {
                    propagateLineScale(sp, idx, c);
                }
            };
            bar.setOnChange(apply);
            attachIntCommit(field, 1, 255, apply);

            gbc.gridx = 1;
            grid.add(field, gbc);

            gbc.gridx = 2;
            gbc.weightx = 1.0;
            gbc.fill = GridBagConstraints.HORIZONTAL;
            grid.add(bar, gbc);
        }

        gbc.gridy = names.length;
        gbc.gridx = 0;
        gbc.weightx = 0;
        gbc.fill = GridBagConstraints.NONE;
        JLabel bstName = new JLabel("Total");
        bstName.setFont(FONT_VALUE);
        bstName.setForeground(TEXT_PRIMARY);
        grid.add(bstName, gbc);

        gbc.gridx = 1;
        bstValueLabel = new JLabel(String.valueOf(sp.getBST()));
        bstValueLabel.setFont(new Font("Segoe UI", Font.BOLD, 13));
        bstValueLabel.setForeground(ACCENT);
        grid.add(bstValueLabel, gbc);

        grid.setAlignmentX(Component.LEFT_ALIGNMENT);
        section.add(grid);

        // --- Evolution-line scaling: propagate a base-stat edit across the whole family ---
        JPanel scaleRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        scaleRow.setOpaque(false);
        final JCheckBox scaleToggle = new JCheckBox("Scale edits across evolution line", lineScaleEnabled);
        scaleToggle.setOpaque(false);
        scaleToggle.setForeground(TEXT_MUTED);
        scaleToggle.setFont(FONT_SMALL);
        scaleToggle.setToolTipText("When on, editing a base stat also adjusts every other Pokémon in this "
                + "evolution family, scaled by the strength slider.");
        final JLabel absLbl = sectionInlineLabel("Absolute");
        final JSlider strength = new JSlider(0, 100, (int) Math.round(lineScaleK * 100));
        strength.setOpaque(false);
        strength.setEnabled(lineScaleEnabled);
        strength.setPreferredSize(new Dimension(110, 20));
        strength.setToolTipText("How hard the change scales onto higher/lower evolutions: "
                + "left = same +/- for all, middle = square-root (balanced), right = same %.");
        final JLabel pctLbl = sectionInlineLabel("Full %");
        final JLabel modeLbl = sectionInlineLabel(lineScaleLabel(lineScaleK));
        scaleToggle.addActionListener(e -> {
            lineScaleEnabled = scaleToggle.isSelected();
            strength.setEnabled(lineScaleEnabled);
        });
        strength.addChangeListener(e -> {
            lineScaleK = strength.getValue() / 100.0;
            modeLbl.setText(lineScaleLabel(lineScaleK));
        });
        JButton resetStatsBtn = new JButton("Reset to default");
        resetStatsBtn.setFont(FONT_SMALL);
        resetStatsBtn.setFocusPainted(false);
        resetStatsBtn.setToolTipText("Restore base stats to the values they had when the ROM was loaded. "
                + "When \"Scale edits across evolution line\" is on, the whole evolution family is reverted; "
                + "otherwise only THIS Pokémon is.");
        resetStatsBtn.addActionListener(e -> resetBaseStats(sp));
        scaleRow.add(scaleToggle);
        scaleRow.add(absLbl);
        scaleRow.add(strength);
        scaleRow.add(pctLbl);
        scaleRow.add(modeLbl);
        scaleRow.add(Box.createHorizontalStrut(12));
        scaleRow.add(resetStatsBtn);
        scaleRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        section.add(scaleRow);

        // EV yields as small number fields
        JPanel evRow = new WrapPanel();
        evRow.add(sectionInlineLabel("EV Yield (0-3):"));
        String[] evNames = { "HP", "Atk", "Def", "Sp.Atk", "Sp.Def", "Speed" };
        int[] evValues = { sp.getHpEvYield(), sp.getAttackEvYield(), sp.getDefenseEvYield(),
                sp.getSpatkEvYield(), sp.getSpdefEvYield(), sp.getSpeedEvYield() };
        IntConsumer[] evSetters = new IntConsumer[] {
                sp::setHpEvYield, sp::setAttackEvYield, sp::setDefenseEvYield,
                sp::setSpatkEvYield, sp::setSpdefEvYield, sp::setSpeedEvYield
        };
        for (int i = 0; i < evNames.length; i++) {
            final IntConsumer evSetter = evSetters[i];
            JPanel cell = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 0));
            cell.setOpaque(false);
            JLabel l = new JLabel(evNames[i]);
            l.setFont(FONT_SMALL);
            l.setForeground(TEXT_MUTED);
            // EV yields are a 2-bit field (0..3); higher values are silently masked
            // away on write-back (Gen5 &0b11, Gen3 min(...,3)), so clamp to 0..3 here.
            JTextField f = plainIntField(evValues[i], 1);
            attachIntCommit(f, 0, 3, v -> { evSetter.accept(v); noteEdit(sp, "EV yield"); });
            cell.add(l);
            cell.add(f);
            evRow.add(cell);
        }
        evRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        section.add(Box.createVerticalStrut(6));
        section.add(evRow);

        return section;
    }

    private JPanel buildAbilitiesItemsSection(Species sp) {
        JPanel section = createSection("Abilities & Held Items");
        JPanel grid = infoGrid();
        int[] rowIdx = { 0 };

        addControlRow(grid, rowIdx, "Ability 1", abilityCombo(abilityIdToOption(sp.getAbility1()),
                id -> { sp.setAbility1(id); noteEdit(sp, "ability 1"); }));
        addControlRow(grid, rowIdx, "Ability 2", abilityCombo(abilityIdToOption(sp.getAbility2()),
                id -> { sp.setAbility2(id); noteEdit(sp, "ability 2"); }));
        if (generation >= 5) {
            addControlRow(grid, rowIdx, "Hidden Ability", abilityCombo(abilityIdToOption(sp.getAbility3()),
                    id -> { sp.setAbility3(id); noteEdit(sp, "hidden ability"); }));
        }

        addControlRow(grid, rowIdx, "Guaranteed Item", itemCombo(sp.getGuaranteedHeldItem(), item -> {
            sp.setGuaranteedHeldItem(item);
            if (item != null) {
                sp.setCommonHeldItem(null);
                sp.setRareHeldItem(null);
                sp.setDarkGrassHeldItem(null);
            }
            noteEdit(sp, "guaranteed item");
            rebuild(sp);
        }));
        addControlRow(grid, rowIdx, "Common Item (50%)", itemCombo(sp.getCommonHeldItem(), item -> {
            if (item != null) {
                sp.setGuaranteedHeldItem(null);
            }
            sp.setCommonHeldItem(item);
            noteEdit(sp, "common item");
        }));
        addControlRow(grid, rowIdx, "Rare Item (5%)", itemCombo(sp.getRareHeldItem(), item -> {
            if (item != null) {
                sp.setGuaranteedHeldItem(null);
            }
            sp.setRareHeldItem(item);
            noteEdit(sp, "rare item");
        }));
        if (generation == 5) {
            addControlRow(grid, rowIdx, "Dark Grass Item", itemCombo(sp.getDarkGrassHeldItem(), item -> {
                if (item != null) {
                    sp.setGuaranteedHeldItem(null);
                }
                sp.setDarkGrassHeldItem(item);
                noteEdit(sp, "dark grass item");
            }));
        }

        grid.setAlignmentX(Component.LEFT_ALIGNMENT);
        section.add(grid);
        return section;
    }

    private JPanel buildBreedingSection(Species sp) {
        JPanel section = createSection("Breeding & Training");
        JPanel grid = infoGrid();
        int[] rowIdx = { 0 };

        // Gender ratio: number field + live percentage label
        JPanel genderCell = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        genderCell.setOpaque(false);
        JTextField genderField = plainIntField(sp.getGenderRatio(), 4);
        genderField.setToolTipText("0 = all male … 254 = all female, 255 = genderless (raw 0-255 value).");
        JLabel genderPct = new JLabel(genderText(sp.getGenderRatio()));
        genderPct.setFont(FONT_LABEL);
        genderPct.setForeground(TEXT_MUTED);
        attachIntCommit(genderField, 0, 255, v -> {
            sp.setGenderRatio(v);
            genderPct.setText(genderText(v));
            noteEdit(sp, "gender ratio");
        });
        genderCell.add(genderField);
        genderCell.add(genderPct);
        addControlRow(grid, rowIdx, "Gender Ratio", genderCell);

        addControlRow(grid, rowIdx, "Egg Group 1", eggGroupCombo(sp.getEggGroup1(),
                v -> { sp.setEggGroup1(v); noteEdit(sp, "egg group 1"); }));
        addControlRow(grid, rowIdx, "Egg Group 2", eggGroupCombo(sp.getEggGroup2(),
                v -> { sp.setEggGroup2(v); noteEdit(sp, "egg group 2"); }));
        addControlRow(grid, rowIdx, "Base Happiness", numberCell(sp.getBaseHappiness(), 0, 255,
                v -> { sp.setBaseHappiness(v); noteEdit(sp, "base happiness"); }));
        addControlRow(grid, rowIdx, "Catch Rate", numberCell(sp.getCatchRate(), 0, 255,
                v -> { sp.setCatchRate(v); noteEdit(sp, "catch rate"); }));
        // Gen3/4 store base exp yield in a single byte (0..255); only Gen5+ use a word.
        addControlRow(grid, rowIdx, "Base Exp. Yield", numberCell(sp.getExpYield(), 0, generation <= 4 ? 255 : 65535,
                v -> { sp.setExpYield(v); noteEdit(sp, "exp yield"); }));
        addControlRow(grid, rowIdx, "Growth Rate", growthCombo(sp.getGrowthCurve(),
                c -> { sp.setGrowthCurve(c); noteEdit(sp, "growth rate"); }));
        if (generation <= 4) {
            // Gen3/4 store the egg HATCH CYCLES in this byte (read/written via callRate),
            // so label it "Egg Cycles" to match the Gen5+ field, not "Hatch Multiplier".
            addControlRow(grid, rowIdx, "Egg Cycles", numberCell(sp.getCallRate(), 0, 255,
                    v -> { sp.setCallRate(v); noteEdit(sp, "egg cycles"); }));
        } else if (generation == 7) {
            addControlRow(grid, rowIdx, "SOS Call Rate", numberCell(sp.getCallRate(), 0, 255,
                    v -> { sp.setCallRate(v); noteEdit(sp, "SOS call rate"); }));
        }
        if (generation >= 5) {
            addControlRow(grid, rowIdx, "Egg Cycles", numberCell(sp.getHatchCounter(), 0, 255,
                    v -> { sp.setHatchCounter(v); noteEdit(sp, "egg cycles"); }));
        }

        grid.setAlignmentX(Component.LEFT_ALIGNMENT);
        section.add(grid);
        return section;
    }

    private JPanel buildMiscSection(Species sp) {
        JPanel section = createSection("Misc Data");
        JPanel grid = infoGrid();
        int[] rowIdx = { 0 };

        if (generation <= 4) {
            addControlRow(grid, rowIdx, "Run Chance", numberCell(sp.getRunChance(), 0, 255,
                    v -> { sp.setRunChance(v); noteEdit(sp, "run chance"); }));
        }
        // Pokédex color shares a sub-byte bitfield with the sprite-flip flag in Gen3 (bit 7)
        // and Gen6/7 (high bits), so an unbounded 0..255 value would clobber the flip bit on save.
        // Cap to the color bits per gen (Gen4/5 store color in its own byte). Valid colors are 0..9.
        int colorMax = generation == 3 ? 127 : (generation >= 6 ? 63 : 255);
        addControlRow(grid, rowIdx, "Pokédex Color", numberCell(sp.getColor(), 0, colorMax,
                v -> { sp.setColor(v); noteEdit(sp, "color"); }));

        JCheckBox flip = new JCheckBox("", sp.isFlip()); // row label "Sprite Flipped" is the caption
        flip.setOpaque(false);
        flip.setForeground(TEXT_PRIMARY);
        flip.setFont(FONT_VALUE);
        flip.addActionListener(e -> {
            if (!building) {
                sp.setFlip(flip.isSelected());
                noteEdit(sp, "flip");
            }
        });
        addControlRow(grid, rowIdx, "Sprite Flipped", flip);

        if (generation >= 5) {
            addControlRow(grid, rowIdx, "Height (dm)", numberCell(sp.getHeight(), 0, 65535,
                    v -> { sp.setHeight(v); noteEdit(sp, "height"); }));
            addControlRow(grid, rowIdx, "Weight (hg)", numberCell(sp.getWeight(), 0, 65535,
                    v -> { sp.setWeight(v); noteEdit(sp, "weight"); }));
        }

        grid.setAlignmentX(Component.LEFT_ALIGNMENT);
        section.add(grid);
        return section;
    }

    private JPanel buildEvolutionSection(Species sp) {
        JPanel section = createSection("Evolutions");
        boolean any = false;

        List<Evolution> from = sp.getEvolutionsTo();
        if (from != null) {
            for (Evolution evo : from) {
                if (evo == null || evo.getFrom() == null) {
                    continue;
                }
                section.add(evolutionRow("Evolves from", evo.getFrom(), describeEvolution(evo)));
                any = true;
            }
        }
        List<Evolution> into = sp.getEvolutionsFrom();
        if (into != null) {
            for (Evolution evo : into) {
                if (evo == null || evo.getTo() == null) {
                    continue;
                }
                section.add(evolutionRow("Evolves into", evo.getTo(), describeEvolution(evo)));
                any = true;
            }
        }
        List<MegaEvolution> megas = sp.getMegaEvolutionsFrom();
        if (megas != null) {
            for (MegaEvolution mega : megas) {
                if (mega == null || mega.getTo() == null) {
                    continue;
                }
                String how = mega.isNeedsItem() && mega.getItem() != null
                        ? "holding " + mega.getItem().getName() : "in battle";
                section.add(evolutionRow("Mega evolves into", mega.getTo(), how));
                any = true;
            }
        }
        if (!any) {
            section.add(mutedLabel("Does not evolve."));
        }
        section.add(Box.createVerticalStrut(4));
        section.add(mutedLabel("Evolutions are shown read-only here — use the Evolutions sheet to edit them."));
        return section;
    }

    private JPanel evolutionRow(String prefix, final Species target, String how) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        row.setOpaque(false);
        JLabel pre = new JLabel(prefix);
        pre.setFont(FONT_LABEL);
        pre.setForeground(TEXT_MUTED);
        row.add(pre);

        ImageIcon icon = iconCache != null ? iconCache.getScaledIcon(target, 28) : null;
        JButton link = new JButton(EditorUtils.speciesNameWithSuffix(target));
        if (icon != null) {
            link.setIcon(icon);
        }
        link.setFont(FONT_VALUE);
        link.setForeground(ACCENT);
        link.setBorderPainted(false);
        link.setContentAreaFilled(false);
        link.setFocusPainted(false);
        link.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        link.setToolTipText("Show " + target.getName() + " in the card view");
        link.addActionListener(e -> navigateTo(target));
        row.add(link);

        JLabel howLabel = new JLabel("— " + how);
        howLabel.setFont(FONT_LABEL);
        howLabel.setForeground(TEXT_PRIMARY);
        row.add(howLabel);

        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        return row;
    }

    private String describeEvolution(Evolution evo) {
        EvolutionType type = evo.getType();
        if (type == null) {
            return "Unknown method";
        }
        String base = prettifyEnumName(type.name());
        int extra = evo.getExtraInfo();
        if (type == EvolutionType.LEVEL_HIGH_BEAUTY) {
            return base + " (beauty >= " + extra + ")";
        }
        if (type == EvolutionType.TRADE_SPECIAL) {
            return extra > 0 ? base + " (trade with " + lookupSpeciesName(extra) + ")" : base;
        }
        if (type.usesLevel()) {
            return base + " (level " + extra + ")";
        }
        if (type.usesItem()) {
            return base + " (" + lookupItemName(extra) + ")";
        }
        if (type.usesMove()) {
            return base + " (knows " + lookupMoveName(extra) + ")";
        }
        if (type.usesSpecies()) {
            return base + " (with " + lookupSpeciesName(extra) + " in party)";
        }
        if (extra > 0) {
            return base + " (" + extra + ")";
        }
        return base;
    }

    // ------------------------------------------------------------------
    // Moves sections (editable)
    // ------------------------------------------------------------------

    private JPanel buildLearnsetSection(final Species sp) {
        List<MoveLearnt> learnset = movesLearnt != null ? movesLearnt.get(sp.getNumber()) : null;
        int count = learnset == null ? 0 : learnset.size();
        JPanel section = createSection("Level-Up Moves (" + count + ")");
        if (movesLearnt == null) {
            section.add(mutedLabel("No level-up move data available."));
            return section;
        }
        if (learnset == null) {
            learnset = new ArrayList<MoveLearnt>();
            movesLearnt.put(sp.getNumber(), learnset);
        }
        final List<MoveLearnt> ls = learnset;

        JPanel grid = new JPanel(new GridBagLayout());
        grid.setOpaque(false);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(1, 0, 1, 8);
        gbc.anchor = GridBagConstraints.WEST;

        for (int r = 0; r < ls.size(); r++) {
            final MoveLearnt ml = ls.get(r);

            gbc.gridy = r;
            gbc.gridx = 0;
            JLabel lvlLabel = new JLabel("Lv.");
            lvlLabel.setFont(FONT_SMALL);
            lvlLabel.setForeground(TEXT_MUTED);
            grid.add(lvlLabel, gbc);

            gbc.gridx = 1;
            JTextField levelField = plainIntField(ml.level, 3);
            attachIntCommit(levelField, 0, 100, v -> { ml.level = v; learnsetDirty = true; });
            grid.add(levelField, gbc);

            gbc.gridx = 2;
            JComboBox<String> moveCombo = moveCombo(ml.move, id -> { ml.move = id; learnsetDirty = true; rebuild(sp); });
            grid.add(moveCombo, gbc);

            gbc.gridx = 3;
            Move mv = lookupMove(ml.move);
            grid.add(mv != null && mv.type != null ? typeChip(mv.type) : chip("???", CHIP_GRAY, TEXT_MUTED), gbc);

            gbc.gridx = 4;
            grid.add(removeButton("Remove this move", () -> {
                ls.remove(ml);
                learnsetDirty = true;
                rebuild(sp);
            }), gbc);
        }
        grid.setAlignmentX(Component.LEFT_ALIGNMENT);
        section.add(grid);

        JComponent add = addButton("+ Add level-up move", () -> {
            Integer moveId = pickMove("Add level-up move");
            if (moveId != null) {
                ls.add(new MoveLearnt(moveId, 1));
                learnsetDirty = true;
                rebuild(sp);
            }
        });
        JPanel addRow = leftRow(add);
        section.add(Box.createVerticalStrut(4));
        section.add(addRow);
        return section;
    }

    private JPanel buildTMSection(final Species sp) {
        final boolean[] compat = tmhmCompatibility != null ? tmhmCompatibility.get(sp) : null;
        int tmCount = tmMoves != null ? tmMoves.size() : 0;
        int hmCount = hmMoves != null ? hmMoves.size() : 0;

        JPanel section = createSection("TM / HM Compatibility");
        if (compat == null || (tmCount == 0 && hmCount == 0)) {
            section.add(mutedLabel("No TM/HM data available."));
            return section;
        }

        WrapPanel tmPanel = new WrapPanel();
        for (int i = 1; i <= tmCount && i < compat.length; i++) {
            if (!compat[i]) {
                continue;
            }
            final int idx = i;
            Move move = lookupMove(tmMoves.get(i - 1));
            String label = String.format("TM%02d %s", i, move != null ? move.name : "?");
            tmPanel.add(removableChip(label, moveColor(move), () -> {
                compat[idx] = false;
                tmDirty = true;
                rebuild(sp);
            }));
        }
        tmPanel.add(addButton("+ Add TM", () -> addCompat(sp, compat, 1, tmCount, "TM", tmMoves, true)));
        tmPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        section.add(labeledBlock("TMs", tmPanel));

        if (hmCount > 0) {
            WrapPanel hmPanel = new WrapPanel();
            for (int i = 1; i <= hmCount; i++) {
                final int compatIdx = tmCount + i;
                if (compatIdx >= compat.length || !compat[compatIdx]) {
                    continue;
                }
                Move move = lookupMove(hmMoves.get(i - 1));
                String label = String.format("HM%02d %s", i, move != null ? move.name : "?");
                hmPanel.add(removableChip(label, moveColor(move), () -> {
                    compat[compatIdx] = false;
                    tmDirty = true;
                    rebuild(sp);
                }));
            }
            hmPanel.add(addButton("+ Add HM", () -> addCompat(sp, compat, tmCount + 1, tmCount + hmCount, "HM", hmMoves, false)));
            hmPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
            section.add(Box.createVerticalStrut(6));
            section.add(labeledBlock("HMs", hmPanel));
        }
        return section;
    }

    private void addCompat(Species sp, boolean[] compat, int from, int to, String prefix,
            List<Integer> moves, boolean tm) {
        List<String> labels = new ArrayList<String>();
        List<Integer> indices = new ArrayList<Integer>();
        for (int i = from; i <= to && i < compat.length; i++) {
            if (!compat[i]) {
                int moveListIdx = tm ? i - 1 : i - from; // HM list is 0-based from its own start
                int displayNum = tm ? i : (i - from + 1);
                Move move = lookupMove(moves.get(moveListIdx));
                labels.add(String.format("%s%02d %s", prefix, displayNum, move != null ? move.name : "?"));
                indices.add(i);
            }
        }
        Integer chosen = pickFromList("Add " + prefix, labels, indices);
        if (chosen != null) {
            compat[chosen] = true;
            tmDirty = true;
            rebuild(sp);
        }
    }

    private JPanel buildTutorSection(final Species sp) {
        final boolean[] compat = tutorCompatibility != null ? tutorCompatibility.get(sp) : null;
        int tutorCount = tutorMoves != null ? tutorMoves.size() : 0;
        JPanel section = createSection("Move Tutors");
        if (compat == null || tutorCount == 0) {
            section.add(mutedLabel("No move tutor data available."));
            return section;
        }
        WrapPanel panel = new WrapPanel();
        for (int i = 1; i <= tutorCount && i < compat.length; i++) {
            if (!compat[i]) {
                continue;
            }
            final int idx = i;
            Move move = lookupMove(tutorMoves.get(i - 1));
            panel.add(removableChip(move != null ? move.name : "?", moveColor(move), () -> {
                compat[idx] = false;
                tutorDirty = true;
                rebuild(sp);
            }));
        }
        panel.add(addButton("+ Add tutor move", () -> {
            List<String> labels = new ArrayList<String>();
            List<Integer> indices = new ArrayList<Integer>();
            for (int i = 1; i <= tutorCount && i < compat.length; i++) {
                if (!compat[i]) {
                    Move move = lookupMove(tutorMoves.get(i - 1));
                    labels.add(move != null ? move.name : "Move #" + tutorMoves.get(i - 1));
                    indices.add(i);
                }
            }
            Integer chosen = pickFromList("Add tutor move", labels, indices);
            if (chosen != null) {
                compat[chosen] = true;
                tutorDirty = true;
                rebuild(sp);
            }
        }));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        section.add(panel);
        return section;
    }

    private JPanel buildEggMovesSection(final Species sp) {
        JPanel section = createSection("Egg Moves");
        if (eggMoves == null) {
            section.add(mutedLabel("No egg move data available."));
            return section;
        }
        List<Integer> moves = eggMoves.get(sp.getNumber());
        if (moves == null) {
            moves = new ArrayList<Integer>();
            eggMoves.put(sp.getNumber(), moves);
        }
        final List<Integer> em = moves;

        WrapPanel panel = new WrapPanel();
        for (int i = 0; i < em.size(); i++) {
            final int slot = i;
            Integer moveId = em.get(i);
            Move move = moveId != null ? lookupMove(moveId) : null;
            panel.add(removableChip(move != null ? move.name : "Move #" + moveId, moveColor(move), () -> {
                em.remove(slot);
                eggDirty = true;
                rebuild(sp);
            }));
        }
        panel.add(addButton("+ Add egg move", () -> {
            Integer moveId = pickMove("Add egg move");
            if (moveId != null) {
                // Skip duplicates and respect the same per-species ceiling the
                // Gen5/Gen6 egg-move sheets enforce on this shared list (Gen6+ = 30,
                // Gen5 = 32); other gens have no defined cap.
                int maxEggMoves = generation >= 6 ? 30 : (generation == 5 ? 32 : Integer.MAX_VALUE);
                if (!em.contains(moveId) && em.size() < maxEggMoves) {
                    em.add(moveId);
                    eggDirty = true;
                    rebuild(sp);
                }
            }
        }));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        section.add(panel);
        return section;
    }

    // ------------------------------------------------------------------
    // Save / persistence
    // ------------------------------------------------------------------

    private void saveChanges() {
        if (saveAction != null) {
            saveAction.run(); // frame.saveAll() — commits sheets + Species, calls our save() last
        } else {
            save();
        }
    }

    /** Called by the editor frame's Save All (placed last so card map edits win). */
    public void save() {
        commitFocus();
        try {
            if (learnsetDirty && movesLearnt != null) {
                for (List<MoveLearnt> list : movesLearnt.values()) {
                    if (list != null) {
                        sortLearnset(list);
                    }
                }
                romHandler.setMovesLearnt(movesLearnt);
                learnsetDirty = false;
            }
            if (eggDirty && eggMoves != null) {
                romHandler.setEggMoves(normalizeEggMoves(eggMoves));
                eggDirty = false;
            }
            if (tmDirty && tmhmCompatibility != null) {
                romHandler.setTMHMCompatibility(tmhmCompatibility);
                tmDirty = false;
            }
            if (tutorDirty && tutorCompatibility != null && romHandler.hasMoveTutors()) {
                romHandler.setMoveTutorCompatibility(tutorCompatibility);
                tutorDirty = false;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        if (!editLog.isEmpty()) {
            ManualEditRegistry.getInstance().addEntries("Card View", new ArrayList<String>(editLog));
            editLog.clear();
        }
    }

    /** Read-only card lifecycle: nothing to revert here (Species reverts are handled by the sheets). */
    public void onWindowClosing() {
        // no-op
    }

    /** Forces any focused number field to commit before saving. */
    private void commitFocus() {
        Component focus = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        if (focus instanceof JTextField) {
            // Commit synchronously (fires the field's action listener) so a value typed and
            // then saved via the File > Save All menu — with no focus change — isn't dropped.
            // transferFocus() posts focus-lost to the EDT queue, which runs AFTER save reads the model.
            ((JTextField) focus).postActionEvent();
        }
    }

    private static void sortLearnset(List<MoveLearnt> list) {
        Collections.sort(list, new Comparator<MoveLearnt>() {
            public int compare(MoveLearnt a, MoveLearnt b) {
                return Integer.compare(a.level, b.level);
            }
        });
    }

    private static Map<Integer, List<Integer>> normalizeEggMoves(Map<Integer, List<Integer>> source) {
        Map<Integer, List<Integer>> out = new java.util.TreeMap<Integer, List<Integer>>();
        for (Map.Entry<Integer, List<Integer>> entry : source.entrySet()) {
            List<Integer> cleaned = new ArrayList<Integer>();
            if (entry.getValue() != null) {
                for (Integer id : entry.getValue()) {
                    if (id != null && id >= 0) {
                        cleaned.add(id);
                    }
                }
            }
            if (!cleaned.isEmpty()) {
                out.put(entry.getKey(), cleaned);
            }
        }
        return out;
    }

    private void noteEdit(Species sp, String what) {
        if (building) {
            return;
        }
        editLog.add(EditorUtils.speciesNameWithSuffix(sp) + ": " + what);
    }

    // ------------------------------------------------------------------
    // Editable control builders
    // ------------------------------------------------------------------

    private JComboBox<String> makeCombo(String[] items, String current, Consumer<String> onSelect) {
        JComboBox<String> combo = new JComboBox<String>(items);
        combo.setSelectedItem(current);
        combo.setFont(FONT_VALUE);
        styleField(combo);
        combo.addActionListener(e -> {
            if (building) {
                return;
            }
            Object sel = combo.getSelectedItem();
            if (sel != null) {
                onSelect.accept(sel.toString());
            }
        });
        return combo;
    }

    private JComboBox<String> abilityCombo(String current, IntConsumer onSelect) {
        JComboBox<String> combo = new JComboBox<String>(abilityOptions);
        combo.setSelectedItem(current);
        combo.setFont(FONT_VALUE);
        styleField(combo);
        EditorUtils.installSearchableComboBox(combo);
        combo.addActionListener(e -> {
            if (building) {
                return;
            }
            Object sel = combo.getSelectedItem();
            if (sel != null) {
                onSelect.accept(parseAbilityId(sel.toString()));
            }
        });
        return combo;
    }

    private JComboBox<String> itemCombo(Item current, Consumer<Item> onSelect) {
        JComboBox<String> combo = new JComboBox<String>(itemOptions);
        combo.setSelectedItem(current == null ? "None" : current.getName());
        combo.setFont(FONT_VALUE);
        styleField(combo);
        EditorUtils.installSearchableComboBox(combo);
        combo.addActionListener(e -> {
            if (building) {
                return;
            }
            Object sel = combo.getSelectedItem();
            if (sel != null) {
                onSelect.accept(findItem(sel.toString()));
            }
        });
        return combo;
    }

    private JComboBox<String> eggGroupCombo(int current, IntConsumer onSelect) {
        JComboBox<String> combo = new JComboBox<String>(EGG_GROUP_NAMES);
        if (current >= 0 && current < EGG_GROUP_NAMES.length) {
            combo.setSelectedIndex(current);
        }
        combo.setFont(FONT_VALUE);
        styleField(combo);
        combo.addActionListener(e -> {
            if (!building) {
                onSelect.accept(combo.getSelectedIndex());
            }
        });
        return combo;
    }

    private JComboBox<String> growthCombo(ExpCurve current, Consumer<ExpCurve> onSelect) {
        JComboBox<String> combo = new JComboBox<String>(growthOptions);
        if (current != null) {
            combo.setSelectedItem(current.name());
        }
        combo.setFont(FONT_VALUE);
        styleField(combo);
        combo.addActionListener(e -> {
            if (building) {
                return;
            }
            Object sel = combo.getSelectedItem();
            if (sel != null) {
                try {
                    onSelect.accept(ExpCurve.valueOf(sel.toString()));
                } catch (Exception ignored) {
                }
            }
        });
        return combo;
    }

    private JComboBox<String> moveCombo(int currentId, IntConsumer onSelect) {
        List<String> opts = new ArrayList<String>();
        Move cur = lookupMove(currentId);
        String currentLabel = currentId + ": " + (cur != null ? cur.name : "?");
        boolean hasCurrent = false;
        if (moveList != null) {
            for (int i = 1; i < moveList.size(); i++) {
                Move m = moveList.get(i);
                if (m != null && m.name != null && !m.name.trim().isEmpty()) {
                    opts.add(i + ": " + m.name);
                    if (i == currentId) {
                        hasCurrent = true;
                    }
                }
            }
        }
        // Always show the row's actual move, even if its id isn't a normal move
        // (otherwise the combo would silently default to the first option).
        if (!hasCurrent) {
            opts.add(0, currentLabel);
        }
        JComboBox<String> combo = new JComboBox<String>(opts.toArray(new String[0]));
        combo.setSelectedItem(currentLabel);
        combo.setFont(FONT_VALUE);
        styleField(combo);
        EditorUtils.installSearchableComboBox(combo);
        combo.addActionListener(e -> {
            if (building) {
                return;
            }
            Object sel = combo.getSelectedItem();
            if (sel != null) {
                onSelect.accept(parseLeadingId(sel.toString()));
            }
        });
        return combo;
    }

    private JComponent numberCell(int value, int min, int max, IntConsumer setter) {
        JTextField field = plainIntField(value, Math.max(3, String.valueOf(max).length()));
        attachIntCommit(field, min, max, setter);
        return field;
    }

    private JTextField plainIntField(int value, int columns) {
        JTextField field = new JTextField(String.valueOf(value), columns);
        field.setFont(FONT_VALUE);
        field.setForeground(TEXT_PRIMARY);
        field.setBackground(FIELD_BG);
        field.setCaretColor(TEXT_PRIMARY);
        field.setHorizontalAlignment(SwingConstants.CENTER);
        return field;
    }

    private void attachIntCommit(JTextField field, int min, int max, IntConsumer setter) {
        Runnable commit = () -> {
            if (building) {
                return;
            }
            int v;
            try {
                v = Integer.parseInt(field.getText().trim());
            } catch (NumberFormatException e) {
                v = min;
            }
            v = clamp(v, min, max);
            field.setText(String.valueOf(v));
            setter.accept(v);
        };
        field.addActionListener(e -> commit.run());
        field.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                commit.run();
            }
        });
    }

    private void styleField(JComponent c) {
        c.setForeground(TEXT_PRIMARY);
        c.setBackground(FIELD_BG);
    }

    /** Label + control on one info-grid row. */
    private void addControlRow(JPanel grid, int[] rowIdx, String label, JComponent control) {
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridy = rowIdx[0];
        gbc.anchor = GridBagConstraints.WEST;
        gbc.insets = new Insets(3, 0, 3, 16);

        gbc.gridx = 0;
        JLabel labelComp = new JLabel(label);
        labelComp.setFont(FONT_LABEL);
        labelComp.setForeground(TEXT_MUTED);
        labelComp.setPreferredSize(new Dimension(140, 22));
        grid.add(labelComp, gbc);

        gbc.gridx = 1;
        gbc.weightx = 1.0;
        grid.add(control, gbc);
        rowIdx[0]++;
    }

    /** Small caption above a control (used in the header for type dropdowns). */
    private JPanel taggedControl(String caption, JComponent control) {
        JPanel p = new JPanel();
        p.setOpaque(false);
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        JLabel l = new JLabel(caption);
        l.setFont(FONT_SMALL);
        l.setForeground(TEXT_MUTED);
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        control.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(l);
        p.add(control);
        return p;
    }

    private JPanel labeledBlock(String caption, JComponent content) {
        JPanel p = new JPanel(new BorderLayout(0, 2));
        p.setOpaque(false);
        JLabel l = new JLabel(caption);
        l.setFont(FONT_SMALL);
        l.setForeground(TEXT_MUTED);
        p.add(l, BorderLayout.NORTH);
        p.add(content, BorderLayout.CENTER);
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        return p;
    }

    private JPanel leftRow(JComponent c) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        p.setOpaque(false);
        p.add(c);
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        return p;
    }

    // ------------------------------------------------------------------
    // Pickers
    // ------------------------------------------------------------------

    private Integer pickMove(String title) {
        List<String> labels = new ArrayList<String>();
        List<Integer> ids = new ArrayList<Integer>();
        if (moveList != null) {
            for (int i = 1; i < moveList.size(); i++) {
                Move m = moveList.get(i);
                if (m != null && m.name != null && !m.name.trim().isEmpty()) {
                    labels.add(i + ": " + m.name);
                    ids.add(i);
                }
            }
        }
        return pickFromList(title, labels, ids);
    }

    private Integer pickFromList(String title, final List<String> labels, final List<Integer> values) {
        if (labels.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Nothing available to add.", title,
                    JOptionPane.INFORMATION_MESSAGE);
            return null;
        }
        // A filter field + JList: selection is unambiguous (an editable
        // searchable combo inside a modal dialog returns unmatched typed text,
        // which silently dropped the addition).
        // Track each visible row's index into `labels` so selection stays correct
        // even when two entries share the same label (e.g. the same tutor move in
        // two slots) — resolving by label text alone would pick the wrong one.
        final DefaultListModel<String> model = new DefaultListModel<String>();
        final List<Integer> modelIndices = new ArrayList<Integer>();
        for (int i = 0; i < labels.size(); i++) {
            model.addElement(labels.get(i));
            modelIndices.add(i);
        }
        final JList<String> list = new JList<String>(model);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setVisibleRowCount(14);
        list.setSelectedIndex(0);

        final JTextField filter = new JTextField();
        filter.getDocument().addDocumentListener(new DocumentListener() {
            private void refilter() {
                String q = filter.getText().trim().toLowerCase();
                model.clear();
                modelIndices.clear();
                for (int i = 0; i < labels.size(); i++) {
                    String l = labels.get(i);
                    if (q.isEmpty() || l.toLowerCase().contains(q)) {
                        model.addElement(l);
                        modelIndices.add(i);
                    }
                }
                if (!model.isEmpty()) {
                    list.setSelectedIndex(0);
                }
            }
            public void insertUpdate(DocumentEvent e) { refilter(); }
            public void removeUpdate(DocumentEvent e) { refilter(); }
            public void changedUpdate(DocumentEvent e) { refilter(); }
        });

        JPanel panel = new JPanel(new BorderLayout(0, 6));
        JPanel top = new JPanel(new BorderLayout(6, 0));
        top.add(new JLabel("Filter:"), BorderLayout.WEST);
        top.add(filter, BorderLayout.CENTER);
        panel.add(top, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(list);
        scroll.setPreferredSize(new Dimension(340, 280));
        panel.add(scroll, BorderLayout.CENTER);

        int result = JOptionPane.showConfirmDialog(this, panel, title,
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION) {
            return null;
        }
        int selRow = list.getSelectedIndex();
        if (selRow < 0 || selRow >= modelIndices.size()) {
            return null;
        }
        int idx = modelIndices.get(selRow);
        return idx >= 0 && idx < values.size() ? values.get(idx) : null;
    }

    // ------------------------------------------------------------------
    // Lookup / parsing helpers
    // ------------------------------------------------------------------

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private Type parseType(String name) {
        try {
            return Type.valueOf(name);
        } catch (Exception e) {
            return null;
        }
    }

    private String abilityIdToOption(int id) {
        if (id <= 0) {
            return "0";
        }
        try {
            String n = romHandler.abilityName(id);
            if (n != null && !n.trim().isEmpty()) {
                return id + ": " + n.trim();
            }
        } catch (Exception ignored) {
        }
        return "0";
    }

    private int parseAbilityId(String option) {
        if (option == null) {
            return 0;
        }
        return parseLeadingId(option);
    }

    private static int parseLeadingId(String option) {
        try {
            int colon = option.indexOf(':');
            return Integer.parseInt((colon >= 0 ? option.substring(0, colon) : option).trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private Item findItem(String name) {
        if (name == null || name.equals("None")) {
            return null;
        }
        if (itemList != null) {
            for (Item item : itemList) {
                if (item != null && item.getName().equals(name)) {
                    return item;
                }
            }
        }
        return null;
    }

    private Move lookupMove(int id) {
        if (moveList == null || id < 0 || id >= moveList.size()) {
            return null;
        }
        return moveList.get(id);
    }

    private String lookupMoveName(int id) {
        Move move = lookupMove(id);
        return move != null ? move.name : "Move #" + id;
    }

    private String lookupItemName(int id) {
        if (itemList != null && id >= 0 && id < itemList.size() && itemList.get(id) != null) {
            return itemList.get(id).getName();
        }
        return "Item #" + id;
    }

    private String lookupSpeciesName(int id) {
        if (pokemonList != null && id >= 0 && id < pokemonList.size() && pokemonList.get(id) != null) {
            return pokemonList.get(id).getName();
        }
        return "Species #" + id;
    }

    private static Color moveColor(Move move) {
        return move != null && move.type != null ? typeColor(move.type) : CHIP_GRAY;
    }

    private static String genderText(int ratio) {
        if (ratio == 255) {
            return "Genderless";
        }
        if (ratio == 254) {
            return "100% Female";
        }
        if (ratio == 0) {
            return "100% Male";
        }
        // Only the raw sentinels 0/254 are truly all-male/all-female; clamp the
        // rounded percentage so near-extreme ratios (e.g. 1 or 253) never display
        // a misleading exact 0%/100% split.
        int femalePct = Math.round(ratio * 100f / 254f);
        femalePct = Math.max(1, Math.min(99, femalePct));
        return femalePct + "% F / " + (100 - femalePct) + "% M";
    }

    private static String prettifyEnumName(String enumName) {
        String[] parts = enumName.toLowerCase().split("_");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // UI building blocks
    // ------------------------------------------------------------------

    private JPanel createSection(String title) {
        JPanel section = new JPanel();
        section.setLayout(new BoxLayout(section, BoxLayout.Y_AXIS));
        section.setBackground(CARD_BG);
        section.setBorder(BorderFactory.createCompoundBorder(
                new RoundedBorder(CARD_BORDER, 10),
                new EmptyBorder(12, 14, 12, 14)));
        section.setAlignmentX(Component.LEFT_ALIGNMENT);
        if (title != null) {
            JPanel titleRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            titleRow.setOpaque(false);
            JPanel accentBar = new JPanel();
            accentBar.setBackground(ACCENT);
            accentBar.setPreferredSize(new Dimension(4, 16));
            titleRow.add(accentBar);
            titleRow.add(Box.createHorizontalStrut(8));
            JLabel titleLabel = new JLabel(title);
            titleLabel.setFont(FONT_SECTION);
            titleLabel.setForeground(TEXT_PRIMARY);
            titleRow.add(titleLabel);
            titleRow.setAlignmentX(Component.LEFT_ALIGNMENT);
            section.add(titleRow);
            section.add(Box.createVerticalStrut(8));
        }
        return section;
    }

    private static JPanel infoGrid() {
        JPanel grid = new JPanel(new GridBagLayout());
        grid.setOpaque(false);
        return grid;
    }

    private static JLabel sectionInlineLabel(String text) {
        JLabel label = new JLabel(text);
        label.setFont(FONT_LABEL);
        label.setForeground(TEXT_MUTED);
        return label;
    }

    private static JLabel mutedLabel(String text) {
        JLabel label = new JLabel(text);
        label.setFont(FONT_SMALL);
        label.setForeground(TEXT_MUTED);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }

    private static JComponent chip(String text, Color background, Color foreground) {
        return new Chip(text, background, foreground, null);
    }

    private JComponent removableChip(String text, Color background, Runnable onRemove) {
        Chip c = new Chip(text + "  ×", background, Color.WHITE, onRemove);
        c.setToolTipText("Click to remove");
        return c;
    }

    private JComponent addButton(String text, Runnable onClick) {
        Chip c = new Chip(text, ACCENT, Color.WHITE, onClick);
        c.setToolTipText("Add");
        return c;
    }

    private JComponent removeButton(String tooltip, Runnable onClick) {
        Chip c = new Chip("×", REMOVE_RED, Color.WHITE, onClick);
        c.setToolTipText(tooltip);
        return c;
    }

    private static JComponent typeChip(Type type) {
        if (type == null) {
            return chip("None", CHIP_GRAY, TEXT_MUTED);
        }
        return chip(prettifyEnumName(type.name()).toUpperCase(), typeColor(type), Color.WHITE);
    }

    /** Standard type colors, matching the Personal Sheet renderer. */
    static Color typeColor(Type type) {
        if (type == null) {
            return CHIP_GRAY;
        }
        switch (type) {
            case NORMAL: return new Color(168, 168, 120);
            case FIGHTING: return new Color(192, 48, 40);
            case FLYING: return new Color(168, 144, 240);
            case POISON: return new Color(160, 64, 160);
            case GROUND: return new Color(224, 192, 104);
            case ROCK: return new Color(184, 160, 56);
            case BUG: return new Color(168, 184, 32);
            case GHOST: return new Color(112, 88, 152);
            case STEEL: return new Color(184, 184, 208);
            case FIRE: return new Color(240, 128, 48);
            case WATER: return new Color(104, 144, 240);
            case GRASS: return new Color(120, 200, 80);
            case ELECTRIC: return new Color(248, 208, 48);
            case PSYCHIC: return new Color(248, 88, 136);
            case ICE: return new Color(152, 216, 216);
            case DRAGON: return new Color(112, 56, 248);
            case DARK: return new Color(112, 88, 72);
            case FAIRY: return new Color(238, 153, 238);
            default: return CHIP_GRAY;
        }
    }

    // ------------------------------------------------------------------
    // Custom components
    // ------------------------------------------------------------------

    /** Rounded colored badge with text; optionally clickable. */
    private static class Chip extends JComponent {
        private final String text;
        private final Color background;
        private final Color foreground;

        Chip(String text, Color background, Color foreground, final Runnable onClick) {
            this.text = text;
            this.background = background;
            this.foreground = foreground;
            setFont(FONT_CHIP);
            if (onClick != null) {
                setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                // mousePressed (not mouseClicked): mouseClicked only fires when
                // press+release land on the exact same pixel, so tiny movement
                // made tag add/remove silently do nothing.
                addMouseListener(new MouseAdapter() {
                    @Override
                    public void mousePressed(MouseEvent e) {
                        onClick.run();
                    }
                });
            }
        }

        @Override
        public Dimension getPreferredSize() {
            FontMetrics fm = getFontMetrics(getFont());
            return new Dimension(fm.stringWidth(text) + 18, fm.getHeight() + 8);
        }

        @Override
        public Dimension getMinimumSize() {
            return getPreferredSize();
        }

        @Override
        public Dimension getMaximumSize() {
            return getPreferredSize();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(background);
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), getHeight(), getHeight());
            g2.setColor(foreground);
            g2.setFont(getFont());
            FontMetrics fm = g2.getFontMetrics();
            int textX = (getWidth() - fm.stringWidth(text)) / 2;
            int textY = (getHeight() + fm.getAscent() - fm.getDescent()) / 2;
            g2.drawString(text, textX, textY);
            g2.dispose();
        }
    }

    /** Draggable horizontal stat bar (1..255), colored by value, with a change callback. */
    private static class StatBar extends JComponent {
        private static final int MAX_STAT = 255;
        private int value;
        private IntConsumer onChange;

        StatBar(int value) {
            this.value = value;
            setCursor(Cursor.getPredefinedCursor(Cursor.W_RESIZE_CURSOR));
            MouseAdapter ma = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    apply(e.getX());
                }

                @Override
                public void mouseDragged(MouseEvent e) {
                    apply(e.getX());
                }
            };
            addMouseListener(ma);
            addMouseMotionListener(ma);
        }

        void setOnChange(IntConsumer onChange) {
            this.onChange = onChange;
        }

        void setValueQuiet(int v) {
            this.value = v;
            repaint();
        }

        private void apply(int x) {
            int w = Math.max(1, getWidth());
            int v = Math.round((float) x / w * MAX_STAT);
            v = Math.max(1, Math.min(MAX_STAT, v));
            this.value = v;
            repaint();
            if (onChange != null) {
                onChange.accept(v);
            }
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(220, 16);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int width = getWidth();
            int height = getHeight();
            g2.setColor(BAR_TRACK);
            g2.fillRoundRect(0, 0, width, height, height, height);
            int filled = Math.min(width, Math.max(2, width * value / MAX_STAT));
            float hue = Math.min(1f, value / 150f) * 0.36f;
            g2.setColor(Color.getHSBColor(hue, 0.72f, 0.85f));
            g2.fillRoundRect(0, 0, filled, height, height, height);
            g2.dispose();
        }
    }

    private static class RoundedBorder extends AbstractBorder {
        private final Color color;
        private final int radius;

        RoundedBorder(Color color, int radius) {
            this.color = color;
            this.radius = radius;
        }

        @Override
        public void paintBorder(Component c, Graphics g, int x, int y, int width, int height) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(color);
            g2.drawRoundRect(x, y, width - 1, height - 1, radius, radius);
            g2.dispose();
        }

        @Override
        public Insets getBorderInsets(Component c) {
            return new Insets(2, 2, 2, 2);
        }

        @Override
        public Insets getBorderInsets(Component c, Insets insets) {
            insets.set(2, 2, 2, 2);
            return insets;
        }
    }

    private static class WrapPanel extends JPanel {
        WrapPanel() {
            super(new WrapLayout(FlowLayout.LEFT, 4, 4));
            setOpaque(false);
        }
    }

    private static class WrapLayout extends FlowLayout {
        WrapLayout(int align, int hgap, int vgap) {
            super(align, hgap, vgap);
        }

        @Override
        public Dimension preferredLayoutSize(Container target) {
            return layoutSize(target, true);
        }

        @Override
        public Dimension minimumLayoutSize(Container target) {
            Dimension minimum = layoutSize(target, false);
            minimum.width -= (getHgap() + 1);
            return minimum;
        }

        private Dimension layoutSize(Container target, boolean preferred) {
            synchronized (target.getTreeLock()) {
                int targetWidth = target.getSize().width;
                Container container = target;
                int insetsAdjust = 0;
                while (container.getSize().width == 0 && container.getParent() != null) {
                    container = container.getParent();
                    Insets containerInsets = container.getInsets();
                    insetsAdjust += containerInsets.left + containerInsets.right;
                }
                targetWidth = container.getSize().width - insetsAdjust;
                if (targetWidth <= 0) {
                    targetWidth = Integer.MAX_VALUE;
                }

                int hgap = getHgap();
                int vgap = getVgap();
                Insets insets = target.getInsets();
                int horizontalInsetsAndGap = insets.left + insets.right + (hgap * 2);
                int maxWidth = targetWidth - horizontalInsetsAndGap;

                Dimension dim = new Dimension(0, 0);
                int rowWidth = 0;
                int rowHeight = 0;

                int memberCount = target.getComponentCount();
                for (int i = 0; i < memberCount; i++) {
                    Component member = target.getComponent(i);
                    if (!member.isVisible()) {
                        continue;
                    }
                    Dimension d = preferred ? member.getPreferredSize() : member.getMinimumSize();
                    if (rowWidth + d.width > maxWidth) {
                        addRow(dim, rowWidth, rowHeight);
                        rowWidth = 0;
                        rowHeight = 0;
                    }
                    if (rowWidth != 0) {
                        rowWidth += hgap;
                    }
                    rowWidth += d.width;
                    rowHeight = Math.max(rowHeight, d.height);
                }
                addRow(dim, rowWidth, rowHeight);

                dim.width += horizontalInsetsAndGap;
                dim.height += insets.top + insets.bottom + vgap * 2;

                Container scrollPane = SwingUtilities.getAncestorOfClass(JScrollPane.class, target);
                if (scrollPane != null && target.isValid()) {
                    dim.width -= (hgap + 1);
                }
                return dim;
            }
        }

        private void addRow(Dimension dim, int rowWidth, int rowHeight) {
            dim.width = Math.max(dim.width, rowWidth);
            if (dim.height > 0) {
                dim.height += getVgap();
            }
            dim.height += rowHeight;
        }
    }

    private static class ScrollableCard extends JPanel implements Scrollable {
        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
            return 16;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
            return Math.max(visibleRect.height - 32, 32);
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    private class SpeciesListRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                boolean isSelected, boolean cellHasFocus) {
            JLabel label = (JLabel) super.getListCellRendererComponent(
                    list, value, index, isSelected, cellHasFocus);
            if (value instanceof Species) {
                Species sp = (Species) value;
                label.setText(String.format("#%03d  %s", sp.getNumber(),
                        EditorUtils.speciesNameWithSuffix(sp)));
                label.setFont(FONT_LABEL);
                label.setIcon(iconCache != null ? iconCache.getScaledIcon(sp, 36) : null);
                label.setIconTextGap(8);
                label.setBorder(new EmptyBorder(0, 6, 0, 6));
                if (!isSelected) {
                    label.setBackground(CARD_BG);
                    label.setForeground(TEXT_PRIMARY);
                }
            }
            return label;
        }
    }
}
