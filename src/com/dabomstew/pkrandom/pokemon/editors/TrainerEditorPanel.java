package com.dabomstew.pkrandom.pokemon.editors;

import com.dabomstew.pkrandom.log.ManualEditRegistry;
import com.dabomstew.pkromio.RomFunctions;
import com.dabomstew.pkromio.gamedata.Item;
import com.dabomstew.pkromio.gamedata.Move;
import com.dabomstew.pkromio.gamedata.MoveLearnt;
import com.dabomstew.pkromio.gamedata.Species;
import com.dabomstew.pkromio.gamedata.Trainer;
import com.dabomstew.pkromio.gamedata.TrainerPokemon;
import com.dabomstew.pkromio.romhandlers.RomHandler;

import javax.swing.*;
import javax.swing.border.AbstractBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.text.AbstractDocument;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DocumentFilter;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Editable Trainer tab: pick a trainer on the left, edit its team (a scrolling
 * list of Pokemon cards) and flags on the right. Only fields that actually
 * persist for the loaded generation are shown (per-gen gating), so there are no
 * dead controls.
 *
 * Persistence mirrors the sheet panels: edits are applied to a working copy of
 * the trainer list and pushed via {@code romHandler.setTrainers(...)} in
 * {@link #save()} (called by the frame's Save All); the actual ROM write happens
 * when the main window saves. Close-without-save reverts the working list.
 */
public class TrainerEditorPanel extends JPanel {

    private static final String[] NATURE_NAMES = {
            "Hardy", "Lonely", "Brave", "Adamant", "Naughty",
            "Bold", "Docile", "Relaxed", "Impish", "Lax",
            "Timid", "Hasty", "Serious", "Jolly", "Naive",
            "Modest", "Mild", "Quiet", "Bashful", "Rash",
            "Calm", "Gentle", "Sassy", "Careful", "Quirky"
    };
    private static final String[] GENDER_NAMES = { "Random", "Male", "Female" };

    private static final Font FONT_TITLE = new Font("Segoe UI", Font.BOLD, 20);
    private static final Font FONT_SECTION = new Font("Segoe UI", Font.BOLD, 14);
    private static final Font FONT_LABEL = new Font("Segoe UI", Font.PLAIN, 12);
    private static final Font FONT_VALUE = new Font("Segoe UI", Font.BOLD, 12);
    private static final Font FONT_SMALL = new Font("Segoe UI", Font.PLAIN, 11);
    private static final Color ACCENT = new Color(0x3B, 0x82, 0xF6);
    private static final Color BOSS_GOLD = new Color(0xD4, 0xA0, 0x17);
    private static final Color IMPORTANT_PURPLE = new Color(0x7C, 0x3A, 0xED);

    private final RomHandler romHandler;
    private final int generation;
    private final PokemonIconCache iconCache;
    private final boolean showAbility;
    private final boolean showGender;
    private final boolean showForme;
    private final boolean showNatureEVs;
    private final int abilitiesPerSpecies;

    // Trainer name / class-name editing (Feature #11).
    private final boolean canChangeText;
    // Per-trainer NAME editing requires getTrainerNames() to be 1:1 with trainer instances.
    // That holds for Gen2-7, but Gen1's getTrainerNames() is a SINGULAR trainer-CLASS list
    // (not one entry per trainer instance), so mapping a trainer's index onto a name slot would
    // rename the wrong trainer/class. Disable the per-trainer Name field for Gen1; class names
    // remain editable via the "Edit Class Names" dialog (setTrainerClassNames is correct there).
    private final boolean perTrainerNames;
    private List<String> trainerNamesWorking;   // mutable copy of getTrainerNames(); pushed in save()
    private List<String> trainerNamesBackup;     // for revert-on-close
    private List<String> classNamesWorking;      // mutable copy of getTrainerClassNames()
    private List<String> classNamesBackup;
    private final java.util.Set<String> nameEditLog = new java.util.LinkedHashSet<String>();
    private final java.util.Set<String> classEditLog = new java.util.LinkedHashSet<String>();
    // Fixed fallback used when the handler doesn't report a usable limit (<=0).
    private static final int DEFAULT_TEXT_LEN = 12;
    // Per-gen text limits sourced from the handler (maxTrainerNameLength / maxTrainerClassNameLength)
    // so games that allow longer names/classes (e.g. Gen2 names up to 17, Gen6/7 classes up to 15)
    // aren't truncated to a hard-coded 12. Resolved once in the constructor.
    private final int maxNameLen;
    private final int maxClassLen;

    private List<Trainer> trainers;          // working list (edited in place)
    private List<Trainer> backup;            // deep copy for revert-on-close
    private List<Species> speciesList;
    private List<Move> moveList;
    private List<Item> itemList;
    private Map<Integer, List<MoveLearnt>> movesLearnt;
    private String[] speciesOptions;
    private String[] itemOptions;
    private String[] moveOptions;

    private boolean building;
    private boolean dirty;
    private Runnable saveAction;
    private final java.util.Set<String> editLog = new java.util.LinkedHashSet<String>();

    private JTextField searchField;
    private JList<Trainer> trainerJList;
    private DefaultListModel<Trainer> listModel;
    private JScrollPane detailScroll;
    private JPanel detailHolder;
    private Trainer currentTrainer;

    public TrainerEditorPanel(RomHandler romHandler) {
        this.romHandler = romHandler;
        this.generation = romHandler.generationOfPokemon();
        this.iconCache = PokemonIconCache.get(romHandler);
        this.showAbility = generation >= 5;   // ability-slot is round-tripped by Gen5/6/7 trainer writers
        this.showGender = generation >= 5;
        // Forme is serialized by the Gen4/5/6/7 trainer writers (Gen3 has no forme byte and
        // hard-codes ability slot 1), so expose the Forme field from Gen4 up. Gen4's writer
        // packs it as (forme << 2) in a single byte, so legal values are tiny — we clamp 0..30.
        this.showForme = generation >= 4;
        this.showNatureEVs = generation >= 7;
        int abilities;
        try {
            abilities = romHandler.abilitiesPerSpecies();
        } catch (Exception e) {
            abilities = 0;
        }
        this.abilitiesPerSpecies = abilities;
        boolean canText;
        try {
            canText = romHandler.canChangeTrainerText();
        } catch (Exception e) {
            canText = false;
        }
        this.canChangeText = canText;
        this.perTrainerNames = canText && romHandler.generationOfPokemon() != 1;
        // Use the handler's reported text limits (fall back to a safe 12 if it reports <=0).
        int nameLen;
        try {
            nameLen = romHandler.maxTrainerNameLength();
        } catch (Exception e) {
            nameLen = 0;
        }
        this.maxNameLen = nameLen > 0 ? nameLen : DEFAULT_TEXT_LEN;
        int classLen;
        try {
            classLen = romHandler.maxTrainerClassNameLength();
        } catch (Exception e) {
            classLen = 0;
        }
        this.maxClassLen = classLen > 0 ? classLen : DEFAULT_TEXT_LEN;
        fetchData();
        buildOptions();
        initUI();
        if (!listModel.isEmpty()) {
            trainerJList.setSelectedIndex(0);
        }
        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentShown(ComponentEvent e) {
                if (currentTrainer != null) {
                    showTrainer(currentTrainer);
                }
            }
        });
    }

    public void setSaveAction(Runnable saveAction) {
        this.saveAction = saveAction;
    }

    private void fetchData() {
        speciesList = romHandler.getSpecies();
        moveList = romHandler.getMoves();
        itemList = romHandler.getItems();
        try {
            movesLearnt = EditorDataCache.get(romHandler).getMovesLearnt();
        } catch (Exception e) {
            movesLearnt = null;
        }
        trainers = new ArrayList<Trainer>(romHandler.getTrainers());
        backup = deepCopy(trainers);

        // Feature #11: snapshot the trainer-name and class-name lists as our working copies.
        // We edit these copies in place and push the WHOLE list (same size, same order) back
        // via setTrainerNames/setTrainerClassNames in save(); the handlers round-trip a list of
        // exactly this shape (e.g. Gen5 keeps PWT/mugshot names on the tail), so we must never
        // resize or reorder it. A trainer with getIndex()==i owns name slot (i-1) in every gen
        // that exposes per-trainer names (Gen3 default list is parallel-by-position; Gen4-7 strip
        // the blank index-0 string, so index-1 lands on the same trainer).
        if (canChangeText) {
            try {
                List<String> names = romHandler.getTrainerNames();
                trainerNamesWorking = names != null ? new ArrayList<String>(names) : new ArrayList<String>();
            } catch (Exception e) {
                trainerNamesWorking = new ArrayList<String>();
            }
            trainerNamesBackup = new ArrayList<String>(trainerNamesWorking);
            try {
                List<String> classes = romHandler.getTrainerClassNames();
                classNamesWorking = classes != null ? new ArrayList<String>(classes) : new ArrayList<String>();
            } catch (Exception e) {
                classNamesWorking = new ArrayList<String>();
            }
            classNamesBackup = new ArrayList<String>(classNamesWorking);
        }
    }

    /**
     * The slot in the trainer-name list that holds {@code t}'s name, or -1 if it has none.
     * Trainer indices are 1-based and gen 4-7 strip the leading blank string, so the name for
     * trainer index {@code i} sits at list position {@code i-1}; the gen 2/3 default list is in
     * getTrainers() order which is the same 1-based sequence, so the rule is uniform.
     */
    private int nameSlot(Trainer t) {
        if (t == null || trainerNamesWorking == null) {
            return -1;
        }
        int slot = t.getIndex() - 1;
        return (slot >= 0 && slot < trainerNamesWorking.size()) ? slot : -1;
    }

    private static List<Trainer> deepCopy(List<Trainer> src) {
        List<Trainer> out = new ArrayList<Trainer>(src.size());
        for (Trainer t : src) {
            out.add(t == null ? null : new Trainer(t));
        }
        return out;
    }

    private void buildOptions() {
        List<String> sp = new ArrayList<String>();
        for (int i = 1; i < speciesList.size(); i++) {
            Species s = speciesList.get(i);
            if (s != null) {
                sp.add(i + ": " + s.getName());
            }
        }
        speciesOptions = sp.toArray(new String[0]);

        List<String> it = new ArrayList<String>();
        it.add("None");
        if (itemList != null) {
            for (Item item : itemList) {
                if (item != null) {
                    // Disambiguate by id (like species/moves): multiple item ids can share a
                    // display name, so resolving by bare name would pick the wrong id.
                    it.add(item.getId() + ": " + item.getName());
                }
            }
        }
        itemOptions = it.toArray(new String[0]);

        List<String> mv = new ArrayList<String>();
        mv.add("0: (none)");
        if (moveList != null) {
            for (int i = 1; i < moveList.size(); i++) {
                Move m = moveList.get(i);
                if (m != null && m.name != null && !m.name.trim().isEmpty()) {
                    mv.add(i + ": " + m.name);
                }
            }
        }
        moveOptions = mv.toArray(new String[0]);
    }

    private void initUI() {
        setLayout(new BorderLayout());
        setBackground(EditorTheme.surface());

        add(buildSidebar(), BorderLayout.WEST);

        detailHolder = new ScrollablePanel();
        detailHolder.setLayout(new BoxLayout(detailHolder, BoxLayout.Y_AXIS));
        detailHolder.setBackground(EditorTheme.surface());
        detailHolder.setBorder(new EmptyBorder(12, 12, 12, 12));

        detailScroll = new JScrollPane(detailHolder);
        detailScroll.setBorder(BorderFactory.createEmptyBorder());
        detailScroll.getVerticalScrollBar().setUnitIncrement(16);
        detailScroll.getViewport().setBackground(EditorTheme.surface());
        add(detailScroll, BorderLayout.CENTER);
    }

    // ------------------------------------------------------------------
    // Sidebar
    // ------------------------------------------------------------------

    private JPanel buildSidebar() {
        JPanel sidebar = new JPanel(new BorderLayout(0, 6));
        sidebar.setPreferredSize(new Dimension(280, 10));
        sidebar.setBackground(EditorTheme.surface());
        sidebar.setBorder(new EmptyBorder(12, 12, 12, 0));

        searchField = new JTextField();
        searchField.setFont(FONT_LABEL);
        searchField.setToolTipText("Search trainers by name, class or index");
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

        listModel = new DefaultListModel<Trainer>();
        for (Trainer t : trainers) {
            if (t != null) {
                listModel.addElement(t);
            }
        }
        trainerJList = new JList<Trainer>(listModel);
        trainerJList.setCellRenderer(new TrainerListRenderer());
        trainerJList.setBackground(EditorTheme.surface());
        trainerJList.setForeground(EditorTheme.text());
        trainerJList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                Trainer sel = trainerJList.getSelectedValue();
                if (sel != null && sel != currentTrainer) {
                    showTrainer(sel);
                }
            }
        });
        JScrollPane listScroll = new JScrollPane(trainerJList);
        listScroll.setBorder(BorderFactory.createLineBorder(EditorTheme.border()));
        listScroll.getVerticalScrollBar().setUnitIncrement(16);
        sidebar.add(listScroll, BorderLayout.CENTER);

        JPanel southButtons = new JPanel();
        southButtons.setLayout(new BoxLayout(southButtons, BoxLayout.Y_AXIS));
        southButtons.setOpaque(false);

        // Feature #11: class names are shared across trainers, so they get their own dialog
        // rather than a per-trainer field. Only offer it when the ROM supports text edits.
        if (canChangeText && classNamesWorking != null && !classNamesWorking.isEmpty()) {
            JButton classButton = new JButton("Edit Class Names…");
            classButton.setFont(FONT_VALUE);
            classButton.setFocusPainted(false);
            classButton.setAlignmentX(Component.CENTER_ALIGNMENT);
            classButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, classButton.getPreferredSize().height));
            classButton.addActionListener(e -> openClassNamesDialog());
            southButtons.add(classButton);
            southButtons.add(Box.createVerticalStrut(6));
        }

        JButton exportButton = new JButton("Export all to .TXT");
        exportButton.setFont(FONT_VALUE);
        exportButton.setFocusPainted(false);
        exportButton.setAlignmentX(Component.CENTER_ALIGNMENT);
        exportButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, exportButton.getPreferredSize().height));
        exportButton.addActionListener(e -> exportToTxt());
        southButtons.add(exportButton);
        sidebar.add(southButtons, BorderLayout.SOUTH);

        return sidebar;
    }

    private void filterList() {
        String q = searchField.getText().trim().toLowerCase();
        Trainer selected = trainerJList.getSelectedValue();
        listModel.clear();
        for (Trainer t : trainers) {
            if (t == null) {
                continue;
            }
            if (q.isEmpty() || trainerLabel(t).toLowerCase().contains(q)) {
                listModel.addElement(t);
            }
        }
        if (selected != null && listModel.contains(selected)) {
            trainerJList.setSelectedValue(selected, true);
        } else if (!listModel.isEmpty()) {
            trainerJList.setSelectedIndex(0);
        }
    }

    private String trainerLabel(Trainer t) {
        String name = t.getFullDisplayName() != null ? t.getFullDisplayName()
                : (t.getName() != null ? t.getName() : "Trainer");
        return "#" + t.getIndex() + " " + name;
    }

    private void rebuild(Trainer t) {
        SwingUtilities.invokeLater(() -> {
            if (currentTrainer == t) {
                showTrainer(t, true); // in-place edit: keep the scroll position
            }
        });
    }

    // ------------------------------------------------------------------
    // Detail
    // ------------------------------------------------------------------

    private void showTrainer(Trainer t) {
        showTrainer(t, false);
    }

    private void showTrainer(Trainer t, boolean preserveScroll) {
        final int previousScroll = preserveScroll
                ? detailScroll.getVerticalScrollBar().getValue() : 0;
        building = true;
        currentTrainer = t;
        detailHolder.removeAll();

        detailHolder.add(buildHeader(t));
        detailHolder.add(Box.createVerticalStrut(10));
        detailHolder.add(buildTrainerInfo(t));
        detailHolder.add(Box.createVerticalStrut(10));

        JPanel teamHeader = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        teamHeader.setOpaque(false);
        teamHeader.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel teamTitle = new JLabel("Team (" + t.getPokemon().size() + ")");
        teamTitle.setFont(FONT_SECTION);
        teamTitle.setForeground(EditorTheme.text());
        teamHeader.add(teamTitle);
        detailHolder.add(teamHeader);
        detailHolder.add(Box.createVerticalStrut(6));

        List<TrainerPokemon> team = t.getPokemon();
        for (int i = 0; i < team.size(); i++) {
            detailHolder.add(buildPokemonCard(t, team.get(i), i));
            detailHolder.add(Box.createVerticalStrut(8));
        }

        if (team.size() < 6) {
            JButton add = new JButton("+ Add Pokémon");
            add.setFont(FONT_VALUE);
            add.setFocusPainted(false);
            add.setAlignmentX(Component.LEFT_ALIGNMENT);
            add.addActionListener(e -> addPokemon(t));
            detailHolder.add(add);
        }

        detailHolder.add(Box.createVerticalStrut(8));
        JLabel hint = new JLabel("Edits apply live — click \"Save Changes\" (top right) to keep them, "
                + "or they revert when the editor closes.");
        hint.setFont(FONT_SMALL);
        hint.setForeground(EditorTheme.mutedText());
        hint.setAlignmentX(Component.LEFT_ALIGNMENT);
        detailHolder.add(hint);

        detailHolder.revalidate();
        detailHolder.repaint();
        SwingUtilities.invokeLater(() -> detailScroll.getVerticalScrollBar().setValue(previousScroll));
        building = false;
    }

    private JPanel buildHeader(Trainer t) {
        JPanel section = section(null);
        JPanel row = new JPanel(new BorderLayout(10, 0));
        row.setOpaque(false);

        JPanel left = new JPanel();
        left.setOpaque(false);
        left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS));

        JLabel name = new JLabel(trainerLabel(t));
        name.setFont(FONT_TITLE);
        name.setForeground(EditorTheme.text());
        name.setAlignmentX(Component.LEFT_ALIGNMENT);
        left.add(name);

        JPanel chips = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        chips.setOpaque(false);
        chips.setAlignmentX(Component.LEFT_ALIGNMENT);
        String className = trainerClassName(t);
        if (className != null) {
            chips.add(chip(className, EditorTheme.border(), EditorTheme.text()));
        }
        if (t.getTag() != null && !t.getTag().isEmpty()) {
            chips.add(chip(t.getTag(), EditorTheme.border(), EditorTheme.text()));
        }
        if (t.isBoss()) {
            chips.add(chip("Boss", BOSS_GOLD, Color.WHITE));
        } else if (t.isImportant()) {
            chips.add(chip("Important", IMPORTANT_PURPLE, Color.WHITE));
        }
        left.add(Box.createVerticalStrut(6));
        left.add(chips);

        JPanel icons = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 4));
        icons.setOpaque(false);
        icons.setAlignmentX(Component.LEFT_ALIGNMENT);
        for (TrainerPokemon tp : t.getPokemon()) {
            if (tp.getSpecies() != null) {
                ImageIcon ic = iconCache != null ? iconCache.getScaledIcon(tp.getSpecies(), 40) : null;
                if (ic != null) {
                    icons.add(new JLabel(ic));
                }
            }
        }
        left.add(icons);

        row.add(left, BorderLayout.CENTER);

        JButton save = new JButton("Save Changes");
        save.setFont(FONT_VALUE);
        save.setFocusPainted(false);
        save.setToolTipText("Commit all trainer edits (same as File → Save All)");
        save.addActionListener(e -> {
            if (saveAction != null) {
                saveAction.run();
            } else {
                save();
            }
        });
        JPanel saveWrap = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        saveWrap.setOpaque(false);
        saveWrap.add(save);
        row.add(saveWrap, BorderLayout.EAST);

        section.add(row);
        return section;
    }

    private JPanel buildTrainerInfo(Trainer t) {
        JPanel section = section("Trainer Settings");

        // Feature #11: editable trainer name (only when the ROM supports trainer-text edits and
        // this trainer actually maps to a name slot). Edits are held in trainerNamesWorking and
        // flushed in save(); a blanked field is ignored so we never write empty text to the ROM.
        final int nameSlot = nameSlot(t);
        if (perTrainerNames && nameSlot >= 0) {
            JPanel nameRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            nameRow.setOpaque(false);
            nameRow.setAlignmentX(Component.LEFT_ALIGNMENT);
            JLabel nameLbl = new JLabel("Name");
            nameLbl.setFont(FONT_LABEL);
            nameLbl.setForeground(EditorTheme.mutedText());
            nameLbl.setPreferredSize(new Dimension(90, 22));
            nameRow.add(nameLbl);

            String cur = trainerNamesWorking.get(nameSlot);
            final JTextField nameField = new JTextField(cur != null ? cur : "", 16);
            nameField.setFont(FONT_VALUE);
            nameField.setForeground(EditorTheme.text());
            nameField.setBackground(EditorTheme.surface());
            nameField.setCaretColor(EditorTheme.text());
            ((AbstractDocument) nameField.getDocument()).setDocumentFilter(new MaxLengthFilter(maxNameLen));
            nameField.setToolTipText("Trainer's display name (max " + maxNameLen
                    + " chars; ROM text buffers are fixed — exact limit varies by game)");
            Runnable commitName = () -> {
                if (building) {
                    return;
                }
                String v = nameField.getText();
                String prev = trainerNamesWorking.get(nameSlot);
                if (v == null || v.trim().isEmpty()) {
                    // Never write empty text — restore the previous value in the field.
                    nameField.setText(prev != null ? prev : "");
                    return;
                }
                if (!v.equals(prev)) {
                    trainerNamesWorking.set(nameSlot, v);
                    dirty = true;
                    nameEditLog.add("#" + t.getIndex() + " name: \"" + (prev != null ? prev : "") + "\" -> \"" + v + "\"");
                }
            };
            nameField.addActionListener(e -> commitName.run());
            nameField.addFocusListener(new FocusAdapter() {
                @Override
                public void focusLost(FocusEvent e) {
                    commitName.run();
                }
            });
            nameRow.add(nameField);
            section.add(nameRow);
            section.add(Box.createVerticalStrut(6));
        }

        JCheckBox items = new JCheckBox("Pokémon have held items", t.pokemonHaveItems());
        styleCheck(items);
        items.addActionListener(e -> {
            if (!building) {
                boolean on = items.isSelected();
                t.setPokemonHaveItems(on);
                if (!on) {
                    // Make the off-switch authoritative: clear held items, otherwise
                    // save() re-derives the flag back on from the leftover data.
                    for (TrainerPokemon p : t.getPokemon()) {
                        p.setHeldItem(null);
                    }
                }
                note(t, "held-items flag");
                rebuild(t);
            }
        });
        section.add(items);

        JCheckBox moves = new JCheckBox("Pokémon have custom moves", t.pokemonHaveCustomMoves());
        styleCheck(moves);
        moves.addActionListener(e -> {
            if (!building) {
                if (moves.isSelected()) {
                    enableCustomMoves(t);
                } else {
                    t.setPokemonHaveCustomMoves(false);
                    // Off-switch authoritative: drop the custom move data so the team
                    // falls back to level-up moves and save() won't re-enable the flag.
                    for (TrainerPokemon p : t.getPokemon()) {
                        p.setMoves(new int[] { 0, 0, 0, 0 });
                        p.setResetMoves(true);
                    }
                }
                note(t, "custom-moves flag");
                rebuild(t);
            }
        });
        section.add(moves);

        JLabel info = new JLabel("Multi-battle: " + t.getMultiBattleStatus()
                + (t.isForcedDoubleBattle() ? " · forced double" : ""));
        info.setFont(FONT_SMALL);
        info.setForeground(EditorTheme.mutedText());
        info.setAlignmentX(Component.LEFT_ALIGNMENT);
        section.add(Box.createVerticalStrut(4));
        section.add(info);

        return section;
    }

    private JPanel buildPokemonCard(final Trainer t, final TrainerPokemon tp, final int slot) {
        JPanel card = section(null);

        // Row 1: icon + species + level + remove
        JPanel top = new JPanel(new GridBagLayout());
        top.setOpaque(false);
        top.setAlignmentX(Component.LEFT_ALIGNMENT);
        GridBagConstraints g = new GridBagConstraints();
        g.gridy = 0;
        g.anchor = GridBagConstraints.WEST;
        g.insets = new Insets(0, 0, 0, 8);

        JLabel icon = new JLabel();
        if (tp.getSpecies() != null && iconCache != null) {
            ImageIcon ic = iconCache.getScaledIcon(tp.getSpecies(), 48);
            if (ic != null) {
                icon.setIcon(ic);
            }
        }
        g.gridx = 0;
        top.add(icon, g);

        g.gridx = 1;
        JLabel slotLabel = new JLabel("#" + (slot + 1));
        slotLabel.setFont(FONT_VALUE);
        slotLabel.setForeground(EditorTheme.mutedText());
        top.add(slotLabel, g);

        g.gridx = 2;
        String curSpecies = tp.getSpecies() != null
                ? tp.getSpecies().getNumber() + ": " + tp.getSpecies().getName() : "";
        JComboBox<String> speciesCombo = searchableCombo(speciesOptions, curSpecies, value -> {
            Species s = speciesByOption(value);
            if (s != null) {
                tp.setSpecies(s);
                note(t, "species");
                rebuild(t);
            }
        });
        speciesCombo.setPreferredSize(new Dimension(190, 26));
        top.add(speciesCombo, g);

        g.gridx = 3;
        JLabel lvl = new JLabel("Lv.");
        lvl.setFont(FONT_LABEL);
        lvl.setForeground(EditorTheme.mutedText());
        top.add(lvl, g);

        g.gridx = 4;
        JTextField levelField = intField(tp.getLevel(), 1, 100, v -> { tp.setLevel(v); note(t, "level"); });
        top.add(levelField, g);

        g.gridx = 5;
        g.weightx = 1.0;
        g.anchor = GridBagConstraints.EAST;
        JButton remove = new JButton("Remove");
        remove.setFont(FONT_SMALL);
        remove.setFocusPainted(false);
        remove.setEnabled(t.getPokemon().size() > 1);
        remove.addActionListener(e -> {
            if (t.getPokemon().size() > 1) {
                t.getPokemon().remove(tp);
                note(t, "removed a Pokémon");
                rebuild(t);
            }
        });
        top.add(remove, g);
        card.add(top);
        card.add(Box.createVerticalStrut(8));

        // Row 2: details grid
        JPanel grid = new JPanel(new GridBagLayout());
        grid.setOpaque(false);
        grid.setAlignmentX(Component.LEFT_ALIGNMENT);
        int[] r = { 0 };

        addRow(grid, r, "Held Item", searchableCombo(itemOptions,
                itemLabel(tp.getHeldItem()), value -> {
                    Item item = itemByOption(value);
                    tp.setHeldItem(item);
                    if (item != null) {
                        t.setPokemonHaveItems(true);
                    }
                    note(t, "held item");
                }));

        // Gen7 packs the IVs into a 32-bit field whose high bits hold flags (bit 30 = shiny),
        // so we must show/edit only the low 5 bits and preserve the rest. Gen6 routes IVs
        // through the 'strength' byte; Gen3/4/5 store the plain value.
        final int ivGen = romHandler.generationOfPokemon();
        int ivDisplay = (ivGen == 7) ? (tp.getIVs() & 0x1F) : tp.getIVs();
        addRow(grid, r, "IVs (0-31)", intField(ivDisplay, 0, 31, v -> {
            if (ivGen == 7) {
                tp.setIVs((tp.getIVs() & ~0x1F) | (v & 0x1F)); // keep shiny flag + other high bits
            } else {
                tp.setIVs(v);
                syncGen6Strength(tp, v); // Gen6 serializes IVs via the 'strength' byte, not getIVs()
            }
            note(t, "IVs");
        }));

        if (showAbility) {
            addRow(grid, r, "Ability", abilityCombo(tp));
        }
        if (showGender) {
            addRow(grid, r, "Gender", indexCombo(GENDER_NAMES,
                    clampIndex(tp.getForcedGenderFlag(), GENDER_NAMES.length),
                    v -> { tp.setForcedGenderFlag(v); note(t, "gender"); }));
        }
        if (showNatureEVs) {
            addRow(grid, r, "Nature", indexCombo(NATURE_NAMES,
                    clampIndex(tp.getNature(), NATURE_NAMES.length),
                    v -> { tp.setNature((byte) v); note(t, "nature"); }));
        }
        if (showForme) {
            // Forme is a small index packed into the trainer entry (Gen4 stores it as forme<<2
            // in one byte), so clamp to a safe 0..30. Show the resolved suffix (e.g. "-Mega")
            // as a read-only hint when the data provides one.
            JTextField formeField = intField(tp.getForme(), 0, 30,
                    v -> { tp.setForme(v); note(t, "forme"); });
            String suffix = tp.getFormeSuffix();
            if (suffix != null && !suffix.isEmpty()) {
                JPanel formeCell = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
                formeCell.setOpaque(false);
                formeCell.add(formeField);
                JLabel suffixLabel = new JLabel(suffix);
                suffixLabel.setFont(FONT_SMALL);
                suffixLabel.setForeground(EditorTheme.mutedText());
                formeCell.add(suffixLabel);
                addRow(grid, r, "Forme #", formeCell);
            } else {
                addRow(grid, r, "Forme #", formeField);
            }
        }

        // Moves — show custom moves if set, otherwise the auto level-up moveset
        boolean customMoves = t.pokemonHaveCustomMoves();
        int[] displayMoves = customMoves ? tp.getMoves() : autoMoves(tp);
        JLabel movesLabel = new JLabel(customMoves ? "Moves" : "Moves (auto)");
        movesLabel.setFont(FONT_LABEL);
        movesLabel.setForeground(EditorTheme.mutedText());
        movesLabel.setToolTipText(customMoves ? null
                : "Auto from level-up. Editing a move switches this trainer to custom moves.");
        JPanel movesRow = new JPanel(new GridLayout(2, 2, 6, 4));
        movesRow.setOpaque(false);
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            int moveId = (displayMoves != null && i < displayMoves.length) ? displayMoves[i] : 0;
            JComboBox<String> moveCombo = searchableCombo(moveOptions, moveLabel(moveId), value -> {
                int id = parseLeadingId(value);
                enableCustomMoves(t); // seed every mon's moveset so nothing blanks out
                int[] m = tp.getMoves();
                if (m != null && idx < m.length) {
                    m[idx] = id;
                    note(t, "moves");
                }
                rebuild(t);
            });
            // In auto mode, editing ANY move flips the whole trainer to custom moves
            // (every member's 4 slots get fixed) — make each combo say so, not just the label.
            if (!customMoves) {
                moveCombo.setToolTipText("Editing any move switches this trainer to custom moves "
                        + "for the whole team (all 4 slots on every Pokémon become fixed).");
            }
            movesRow.add(moveCombo);
        }
        GridBagConstraints gc = new GridBagConstraints();
        gc.gridy = r[0];
        gc.gridx = 0;
        gc.anchor = GridBagConstraints.NORTHWEST;
        gc.insets = new Insets(3, 0, 3, 16);
        grid.add(movesLabel, gc);
        gc.gridx = 1;
        gc.weightx = 1.0;
        gc.fill = GridBagConstraints.HORIZONTAL;
        grid.add(movesRow, gc);
        r[0]++;

        if (showNatureEVs) {
            JLabel evLabel = new JLabel("EVs (0-255)");
            evLabel.setFont(FONT_LABEL);
            evLabel.setForeground(EditorTheme.mutedText());
            JPanel evRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            evRow.setOpaque(false);
            String[] evNames = { "HP", "Atk", "Def", "SpA", "SpD", "Spe" };
            IntConsumer[] evSet = new IntConsumer[] {
                    v -> tp.setHpEVs((byte) v), v -> tp.setAtkEVs((byte) v), v -> tp.setDefEVs((byte) v),
                    v -> tp.setSpatkEVs((byte) v), v -> tp.setSpdefEVs((byte) v), v -> tp.setSpeedEVs((byte) v)
            };
            int[] evVal = { ub(tp.getHpEVs()), ub(tp.getAtkEVs()), ub(tp.getDefEVs()),
                    ub(tp.getSpatkEVs()), ub(tp.getSpdefEVs()), ub(tp.getSpeedEVs()) };
            for (int i = 0; i < 6; i++) {
                final IntConsumer setter = evSet[i];
                JPanel cell = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
                cell.setOpaque(false);
                JLabel l = new JLabel(evNames[i]);
                l.setFont(FONT_SMALL);
                l.setForeground(EditorTheme.mutedText());
                cell.add(l);
                cell.add(intField(evVal[i], 0, 255, v -> { setter.accept(v); note(t, "EVs"); }));
                evRow.add(cell);
            }
            gc.gridy = r[0];
            gc.gridx = 0;
            gc.weightx = 0;
            gc.fill = GridBagConstraints.NONE;
            grid.add(evLabel, gc);
            gc.gridx = 1;
            gc.weightx = 1.0;
            gc.fill = GridBagConstraints.HORIZONTAL;
            grid.add(evRow, gc);
            r[0]++;
        }

        card.add(grid);
        return card;
    }

    private void addPokemon(Trainer t) {
        if (t.getPokemon().size() >= 6) {
            return;
        }
        TrainerPokemon tp = new TrainerPokemon();
        Species first = null;
        for (int i = 1; i < speciesList.size(); i++) {
            if (speciesList.get(i) != null) {
                first = speciesList.get(i);
                break;
            }
        }
        tp.setSpecies(first);
        tp.setLevel(5);
        tp.setIVs(0);
        if (showAbility) {
            tp.setAbilitySlot(1);
        }
        tp.setResetMoves(true);
        t.getPokemon().add(tp);
        note(t, "added a Pokémon");
        rebuild(t);
    }

    /** Switches a trainer to custom moves, seeding every still-blank mon with its level-up moveset. */
    private void enableCustomMoves(Trainer t) {
        if (t.pokemonHaveCustomMoves()) {
            // Already custom — but a freshly added mon may still carry resetMoves=true,
            // which makes the writers ignore its explicit moves and recompute level-up
            // moves instead. Clear it so the custom moveset actually persists.
            for (TrainerPokemon p : t.getPokemon()) {
                p.setResetMoves(false);
            }
            return;
        }
        for (TrainerPokemon p : t.getPokemon()) {
            int[] m = p.getMoves();
            boolean blank = true;
            if (m != null) {
                for (int v : m) {
                    if (v != 0) {
                        blank = false;
                        break;
                    }
                }
            }
            if (blank) {
                p.setMoves(autoMoves(p));
            }
            p.setResetMoves(false); // honor the explicit moveset on save (writers skip getMoves() when resetMoves is true)
        }
        t.setPokemonHaveCustomMoves(true);
    }

    /**
     * Gen 6 (XY/ORAS) does not serialize {@code getIVs()} for trainer Pokémon — the
     * reader derives IVs from the {@code strength} byte and the writer emits only
     * {@code strength}. So an IV edit is dropped unless we also update strength the
     * way the reader expects: ORAS scales 0..31 across 0..255; XY stores the IV in
     * the low 5 bits (preserving the unknown high bits).
     */
    private void syncGen6Strength(TrainerPokemon tp, int ivs) {
        if (romHandler.generationOfPokemon() != 6) {
            return;
        }
        if (romHandler.isORAS()) {
            tp.setStrength(Math.min(255, (int) Math.round(ivs * 255.0 / 31.0)));
        } else {
            tp.setStrength((tp.getStrength() & ~0x1F) | (ivs & 0x1F));
        }
    }

    /** The moves a trainer Pokemon would auto-learn from its level-up set at its level. */
    private int[] autoMoves(TrainerPokemon tp) {
        try {
            if (tp.getSpecies() != null && movesLearnt != null
                    && movesLearnt.get(tp.getSpecies().getNumber()) != null) {
                return RomFunctions.getMovesAtLevel(tp.getSpecies().getNumber(), movesLearnt, tp.getLevel());
            }
        } catch (Exception ignored) {
        }
        return new int[] { 0, 0, 0, 0 };
    }

    // ------------------------------------------------------------------
    // Save / persistence
    // ------------------------------------------------------------------

    public void save() {
        commitFocus();
        // Keep poketype flags consistent with the data actually present.
        for (Trainer t : trainers) {
            if (t == null) {
                continue;
            }
            boolean anyItem = false;
            boolean anyMove = false;
            for (TrainerPokemon tp : t.getPokemon()) {
                if (tp.getHeldItem() != null) {
                    anyItem = true;
                }
                int[] m = tp.getMoves();
                if (m != null) {
                    for (int mv : m) {
                        if (mv != 0) {
                            anyMove = true;
                        }
                    }
                }
            }
            // Make the flags authoritative in BOTH directions: clearing every held
            // item (set each to "None") or zeroing all custom moves manually — without
            // unchecking the box — would otherwise leave a stale flag ON, causing the
            // writers to emit zero-filled item/move blocks that no longer match the data.
            t.setPokemonHaveItems(anyItem);
            t.setPokemonHaveCustomMoves(anyMove);
        }
        // Feature #11: sync edited names into the shared Trainer objects BEFORE setTrainers, so
        // gens that persist the name through the trainer entry (Gen3 writes Trainer.name in
        // setTrainers) pick up the edit. For Gen4-7 this is harmless (their setTrainers ignores
        // Trainer.name; the names live in the string table written by setTrainerNames below).
        if (perTrainerNames && trainerNamesWorking != null) {
            for (Trainer t : trainers) {
                int slot = nameSlot(t);
                if (slot >= 0) {
                    String nm = trainerNamesWorking.get(slot);
                    if (nm != null && !nm.trim().isEmpty()) {
                        t.name = nm;
                    }
                }
            }
        }
        boolean saved = true;
        try {
            romHandler.setTrainers(trainers);
        } catch (Exception e) {
            e.printStackTrace();
            saved = false;
            // Surface the failure (always, even during Save All) and do NOT re-baseline below, so
            // the edits stay revertible and the user isn't told a failed save succeeded.
            JOptionPane.showMessageDialog(this,
                    "Failed to save trainers:\n" + e.getMessage(),
                    "Save Failed", JOptionPane.ERROR_MESSAGE);
        }
        // Feature #11: push the full (same-size, same-order) name/class lists. Done after
        // setTrainers so the string-table write is the authoritative one for Gen4-7. Per-trainer
        // names only when the gen maps names 1:1 to trainers (perTrainerNames); class names whenever
        // text is editable.
        if (saved && canChangeText) {
            try {
                if (perTrainerNames && trainerNamesWorking != null && !trainerNamesWorking.isEmpty()) {
                    // setTrainerNames mutates the passed list in some gens (Gen5 removes mugshot
                    // entries from the tail), so hand it a defensive copy and keep our master intact.
                    romHandler.setTrainerNames(new ArrayList<String>(trainerNamesWorking));
                }
                if (classNamesWorking != null && !classNamesWorking.isEmpty()) {
                    romHandler.setTrainerClassNames(new ArrayList<String>(classNamesWorking));
                }
            } catch (Exception e) {
                e.printStackTrace();
                JOptionPane.showMessageDialog(this,
                        "Trainer names/classes could not be saved: " + e.getMessage(),
                        "Save warning", JOptionPane.WARNING_MESSAGE);
            }
        }
        if (!saved) {
            return; // failed save: skip logging + re-baseline so the edits remain revertible
        }
        if (!editLog.isEmpty()) {
            ManualEditRegistry.getInstance().addEntries("Trainers", new ArrayList<String>(editLog));
            editLog.clear();
        }
        if (!nameEditLog.isEmpty()) {
            ManualEditRegistry.getInstance().addEntries("Trainer Names", new ArrayList<String>(nameEditLog));
            nameEditLog.clear();
        }
        if (!classEditLog.isEmpty()) {
            ManualEditRegistry.getInstance().addEntries("Trainer Classes", new ArrayList<String>(classEditLog));
            classEditLog.clear();
        }
        backup = deepCopy(trainers); // re-baseline so close won't revert saved edits
        // Re-baseline the name/class backups too, so closing after a save doesn't revert them.
        if (trainerNamesWorking != null) {
            trainerNamesBackup = new ArrayList<String>(trainerNamesWorking);
        }
        if (classNamesWorking != null) {
            classNamesBackup = new ArrayList<String>(classNamesWorking);
        }
    }

    /** Reverts unsaved edits (important for Gen 3 where getTrainers() is the live cache). */
    public void onWindowClosing() {
        commitFocus();
        for (int i = 0; i < trainers.size() && i < backup.size(); i++) {
            Trainer w = trainers.get(i);
            Trainer b = backup.get(i);
            if (w == null || b == null) {
                continue;
            }
            w.poketype = b.poketype;
            w.name = b.name;
            List<TrainerPokemon> revert = new ArrayList<TrainerPokemon>();
            for (TrainerPokemon tp : b.getPokemon()) {
                revert.add(new TrainerPokemon(tp));
            }
            w.pokemon = revert;
        }
        // Feature #11: drop unsaved name/class edits. Nothing was written to the ROM yet (that
        // only happens in save()), so restoring the working lists from their backups is enough.
        // The shared Trainer.name fields are already reverted from `backup` above, which matters
        // for Gen3 where getTrainers() is the live cache.
        if (trainerNamesWorking != null && trainerNamesBackup != null) {
            trainerNamesWorking = new ArrayList<String>(trainerNamesBackup);
        }
        if (classNamesWorking != null && classNamesBackup != null) {
            classNamesWorking = new ArrayList<String>(classNamesBackup);
        }
        nameEditLog.clear();
        classEditLog.clear();
    }

    private void commitFocus() {
        Component focus = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        if (focus instanceof JTextField) {
            ((JTextField) focus).postActionEvent(); // commit synchronously so menu Save All doesn't drop a typed value
        }
    }

    private void note(Trainer t, String what) {
        if (!building) {
            dirty = true;
            editLog.add(trainerLabel(t) + ": " + what);
        }
    }

    private void exportToTxt() {
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new java.io.File("trainers.txt"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        try (BufferedWriter w = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(chooser.getSelectedFile()), StandardCharsets.UTF_8))) {
            for (Trainer t : trainers) {
                if (t == null) {
                    continue;
                }
                w.write(trainerLabel(t));
                String cls = trainerClassName(t);
                if (cls != null) {
                    w.write(" [" + cls + "]");
                }
                w.write(System.lineSeparator());
                for (TrainerPokemon tp : t.getPokemon()) {
                    StringBuilder sb = new StringBuilder("    ");
                    sb.append(tp.getSpecies() != null ? tp.getSpecies().getName() : "?");
                    sb.append(" Lv").append(tp.getLevel());
                    if (tp.getHeldItem() != null) {
                        sb.append(" @").append(tp.getHeldItem().getName());
                    }
                    if (t.pokemonHaveCustomMoves()) {
                        List<String> mn = new ArrayList<String>();
                        for (int mv : tp.getMoves()) {
                            if (mv != 0) {
                                Move m = lookupMove(mv);
                                mn.add(m != null ? m.name : ("Move#" + mv));
                            }
                        }
                        if (!mn.isEmpty()) {
                            sb.append(" [").append(String.join(", ", mn)).append("]");
                        }
                    }
                    w.write(sb.toString());
                    w.write(System.lineSeparator());
                }
                w.write(System.lineSeparator());
            }
            JOptionPane.showMessageDialog(this, "Exported " + trainers.size() + " trainers.",
                    "Export complete", JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Export failed: " + e.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * Feature #11: modal table of every (shared) trainer class name. On OK we copy the edited
     * cells back into classNamesWorking (kept the same size/order) — the actual ROM write happens
     * later in save() via setTrainerClassNames. Blank cells are ignored to avoid writing empty
     * text into fixed ROM buffers.
     */
    private void openClassNamesDialog() {
        if (classNamesWorking == null || classNamesWorking.isEmpty()) {
            return;
        }
        final List<String> staging = new ArrayList<String>(classNamesWorking);
        final AbstractTableModel model = new AbstractTableModel() {
            @Override
            public int getRowCount() {
                return staging.size();
            }

            @Override
            public int getColumnCount() {
                return 2;
            }

            @Override
            public String getColumnName(int column) {
                return column == 0 ? "Index" : "Class Name";
            }

            @Override
            public boolean isCellEditable(int rowIndex, int columnIndex) {
                return columnIndex == 1;
            }

            @Override
            public Object getValueAt(int rowIndex, int columnIndex) {
                if (columnIndex == 0) {
                    return rowIndex;
                }
                String v = staging.get(rowIndex);
                return v != null ? v : "";
            }

            @Override
            public void setValueAt(Object aValue, int rowIndex, int columnIndex) {
                if (columnIndex != 1) {
                    return;
                }
                String v = aValue != null ? aValue.toString() : "";
                if (v.length() > maxClassLen) {
                    v = v.substring(0, maxClassLen);
                }
                // Never blank a class name — keep the previous value if the cell is cleared.
                if (v.trim().isEmpty()) {
                    return;
                }
                staging.set(rowIndex, v);
                fireTableCellUpdated(rowIndex, columnIndex);
            }
        };

        final JTable table = new JTable(model);
        table.setRowHeight(22);
        table.getColumnModel().getColumn(0).setMaxWidth(60);
        table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        JScrollPane sp = new JScrollPane(table);
        sp.setPreferredSize(new Dimension(360, 420));

        JLabel hint = new JLabel("Class names are shared by all trainers. Max " + maxClassLen
                + " chars (exact ROM limit varies by game).");
        hint.setFont(FONT_SMALL);
        hint.setForeground(EditorTheme.mutedText());
        hint.setBorder(new EmptyBorder(0, 2, 6, 2));

        JPanel content = new JPanel(new BorderLayout());
        content.add(hint, BorderLayout.NORTH);
        content.add(sp, BorderLayout.CENTER);

        int result = JOptionPane.showConfirmDialog(this, content, "Edit Class Names",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION) {
            return;
        }
        if (table.isEditing()) {
            table.getCellEditor().stopCellEditing();
        }
        // Apply staged edits to the working list (same size/order) and log the diffs.
        for (int i = 0; i < staging.size() && i < classNamesWorking.size(); i++) {
            String before = classNamesWorking.get(i);
            String after = staging.get(i);
            if (after != null && !after.equals(before)) {
                classNamesWorking.set(i, after);
                dirty = true;
                classEditLog.add("class #" + i + ": \"" + (before != null ? before : "")
                        + "\" -> \"" + after + "\"");
            }
        }
        // Refresh chips on the current trainer so a renamed class shows immediately.
        if (currentTrainer != null) {
            rebuild(currentTrainer);
        }
    }

    /** DocumentFilter that hard-caps a text field's length (ROM text buffers are fixed-size). */
    private static class MaxLengthFilter extends DocumentFilter {
        private final int max;

        MaxLengthFilter(int max) {
            this.max = max;
        }

        @Override
        public void insertString(FilterBypass fb, int offset, String string, AttributeSet attr)
                throws BadLocationException {
            if (string == null) {
                return;
            }
            int room = max - fb.getDocument().getLength();
            if (room <= 0) {
                return;
            }
            super.insertString(fb, offset, string.length() > room ? string.substring(0, room) : string, attr);
        }

        @Override
        public void replace(FilterBypass fb, int offset, int length, String text, AttributeSet attrs)
                throws BadLocationException {
            if (text == null) {
                super.replace(fb, offset, length, text, attrs);
                return;
            }
            int room = max - (fb.getDocument().getLength() - length);
            if (room <= 0) {
                super.replace(fb, offset, length, "", attrs);
                return;
            }
            super.replace(fb, offset, length, text.length() > room ? text.substring(0, room) : text, attrs);
        }
    }

    // ------------------------------------------------------------------
    // Control builders
    // ------------------------------------------------------------------

    private JComboBox<String> abilityCombo(final TrainerPokemon tp) {
        List<String> opts = new ArrayList<String>();
        opts.add("0: Default");
        Species s = tp.getSpecies();
        int max = Math.max(1, abilitiesPerSpecies);
        for (int slot = 1; slot <= max; slot++) {
            String aName = "?";
            if (s != null) {
                int id = slot == 1 ? s.getAbility1() : slot == 2 ? s.getAbility2() : s.getAbility3();
                aName = id > 0 ? safeAbilityName(id) : "(none)";
            }
            opts.add(slot + ": " + aName);
        }
        int sel = clampIndex(tp.getAbilitySlot(), opts.size());
        return indexCombo(opts.toArray(new String[0]), sel,
                v -> { tp.setAbilitySlot(v); note(currentTrainer, "ability"); });
    }

    private JComboBox<String> searchableCombo(String[] options, String current, Consumer<String> onSelect) {
        JComboBox<String> combo = new JComboBox<String>(options);
        combo.setSelectedItem(current);
        combo.setFont(FONT_VALUE);
        combo.setForeground(EditorTheme.text());
        combo.setBackground(EditorTheme.surface());
        EditorUtils.installSearchableComboBox(combo);
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

    private JComboBox<String> indexCombo(String[] options, int selectedIndex, IntConsumer onSelect) {
        JComboBox<String> combo = new JComboBox<String>(options);
        if (selectedIndex >= 0 && selectedIndex < options.length) {
            combo.setSelectedIndex(selectedIndex);
        }
        combo.setFont(FONT_VALUE);
        combo.setForeground(EditorTheme.text());
        combo.setBackground(EditorTheme.surface());
        combo.addActionListener(e -> {
            if (!building) {
                onSelect.accept(combo.getSelectedIndex());
            }
        });
        return combo;
    }

    private JTextField intField(int value, int min, int max, IntConsumer setter) {
        JTextField field = new JTextField(String.valueOf(value), Math.max(3, String.valueOf(max).length()));
        field.setFont(FONT_VALUE);
        field.setForeground(EditorTheme.text());
        field.setBackground(EditorTheme.surface());
        field.setCaretColor(EditorTheme.text());
        field.setHorizontalAlignment(SwingConstants.CENTER);
        Runnable commit = () -> {
            if (building) {
                return;
            }
            int v;
            try {
                v = Integer.parseInt(field.getText().trim());
            } catch (NumberFormatException ex) {
                v = min;
            }
            v = Math.max(min, Math.min(max, v));
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
        return field;
    }

    private JPanel section(String title) {
        JPanel s = new JPanel();
        s.setLayout(new BoxLayout(s, BoxLayout.Y_AXIS));
        s.setBackground(EditorTheme.altRow());
        s.setBorder(BorderFactory.createCompoundBorder(
                new RoundedBorder(EditorTheme.border(), 10),
                new EmptyBorder(12, 14, 12, 14)));
        s.setAlignmentX(Component.LEFT_ALIGNMENT);
        if (title != null) {
            JLabel titleLabel = new JLabel(title);
            titleLabel.setFont(FONT_SECTION);
            titleLabel.setForeground(EditorTheme.text());
            titleLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            s.add(titleLabel);
            s.add(Box.createVerticalStrut(8));
        }
        return s;
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

    private JComponent chip(String text, Color bg, Color fg) {
        return new Chip(text, bg, fg);
    }

    // ------------------------------------------------------------------
    // Lookups
    // ------------------------------------------------------------------

    private String trainerClassName(Trainer t) {
        try {
            // Prefer our working copy so unsaved class-name edits show in the UI immediately;
            // fall back to the live ROM list when text editing isn't supported.
            List<String> names = (classNamesWorking != null && !classNamesWorking.isEmpty())
                    ? classNamesWorking : romHandler.getTrainerClassNames();
            int c = t.getTrainerclass();
            if (names != null && c >= 0 && c < names.size()) {
                String n = names.get(c);
                if (n != null && !n.trim().isEmpty()) {
                    return n.trim();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private Species speciesByOption(String option) {
        int id = parseLeadingId(option);
        if (speciesList != null && id >= 0 && id < speciesList.size()) {
            return speciesList.get(id);
        }
        return null;
    }

    // Resolve a held-item combo selection back to an Item by its leading id, not its
    // display name. Item identity is id-based and multiple ids can share a name, so
    // matching on name would silently re-assign the first same-named id.
    private Item itemByOption(String option) {
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

    // Display label for the held-item combo, matching the disambiguated "id: name"
    // option strings produced by buildOptions so the current selection highlights.
    private String itemLabel(Item item) {
        if (item == null) {
            return "None";
        }
        return item.getId() + ": " + item.getName();
    }

    private Move lookupMove(int id) {
        if (moveList == null || id < 0 || id >= moveList.size()) {
            return null;
        }
        return moveList.get(id);
    }

    private String moveLabel(int id) {
        Move m = lookupMove(id);
        if (id == 0) {
            return "0: (none)";
        }
        return id + ": " + (m != null ? m.name : "?");
    }

    private String safeAbilityName(int id) {
        try {
            String n = romHandler.abilityName(id);
            return n != null && !n.trim().isEmpty() ? n.trim() : ("#" + id);
        } catch (Exception e) {
            return "#" + id;
        }
    }

    private static int parseLeadingId(String option) {
        if (option == null) {
            return 0;
        }
        try {
            int colon = option.indexOf(':');
            return Integer.parseInt((colon >= 0 ? option.substring(0, colon) : option).trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private static int ub(byte b) {
        return b & 0xFF;
    }

    private static int clampIndex(int v, int size) {
        return v < 0 ? 0 : (v >= size ? 0 : v);
    }

    // ------------------------------------------------------------------
    // Components
    // ------------------------------------------------------------------

    private static class Chip extends JComponent {
        private final String text;
        private final Color background;
        private final Color foreground;

        Chip(String text, Color background, Color foreground) {
            this.text = text;
            this.background = background;
            this.foreground = foreground;
            setFont(FONT_SMALL);
        }

        @Override
        public Dimension getPreferredSize() {
            FontMetrics fm = getFontMetrics(getFont());
            return new Dimension(fm.stringWidth(text) + 16, fm.getHeight() + 6);
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
            g2.drawString(text, (getWidth() - fm.stringWidth(text)) / 2,
                    (getHeight() + fm.getAscent() - fm.getDescent()) / 2);
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

    private static class ScrollablePanel extends JPanel implements Scrollable {
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

    private class TrainerListRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                boolean isSelected, boolean cellHasFocus) {
            JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value instanceof Trainer) {
                Trainer t = (Trainer) value;
                label.setText(trainerLabel(t));
                label.setFont(FONT_LABEL);
                label.setBorder(new EmptyBorder(3, 6, 3, 6));
                if (!isSelected) {
                    label.setBackground(EditorTheme.surface());
                    label.setForeground(EditorTheme.text());
                }
            }
            return label;
        }
    }
}
