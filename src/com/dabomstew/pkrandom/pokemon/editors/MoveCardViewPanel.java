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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Per-Move "card" view: shows the editable values the Moves sheets expose and
 * lets the user edit them inline with a clean UI — number fields for numerics,
 * dropdowns for enumerated values, and checkboxes for boolean flags.
 *
 * <p>Persistence differs from the Pokemon card: {@link RomHandler#getMoves()}
 * returns the LIVE list (backed by the internal array), so every control writes
 * the field directly on the live {@link Move} object. There is no move-persisting
 * setter on the RomHandler — the main-window ROM save reads the live Move objects.
 * {@link #save()} only records the edit log.
 */
public class MoveCardViewPanel extends JPanel {

    // ---- palette (light/dark variants, synced from ThemeManager) ----
    private static Color BG = new Color(0xF4, 0xF5, 0xF7);
    private static Color CARD_BG = Color.WHITE;
    private static Color CARD_BORDER = new Color(0xDD, 0xDF, 0xE3);
    private static Color TEXT_PRIMARY = new Color(0x21, 0x25, 0x29);
    private static Color TEXT_MUTED = new Color(0x6C, 0x75, 0x7D);
    private static Color CHIP_GRAY = new Color(0xE9, 0xEC, 0xEF);
    private static Color FIELD_BG = Color.WHITE;
    private static final Color ACCENT = new Color(0x3B, 0x82, 0xF6);

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
            FIELD_BG = new Color(0x2A, 0x2D, 0x2F);
        } else {
            BG = new Color(0xF4, 0xF5, 0xF7);
            CARD_BG = Color.WHITE;
            CARD_BORDER = new Color(0xDD, 0xDF, 0xE3);
            TEXT_PRIMARY = new Color(0x21, 0x25, 0x29);
            TEXT_MUTED = new Color(0x6C, 0x75, 0x7D);
            CHIP_GRAY = new Color(0xE9, 0xEC, 0xEF);
            FIELD_BG = Color.WHITE;
        }
    }

    private static final Font FONT_TITLE = new Font("Segoe UI", Font.BOLD, 28);
    private static final Font FONT_SECTION = new Font("Segoe UI", Font.BOLD, 16);
    private static final Font FONT_LABEL = new Font("Segoe UI", Font.PLAIN, 14);
    private static final Font FONT_VALUE = new Font("Segoe UI", Font.BOLD, 14);
    private static final Font FONT_CHIP = new Font("Segoe UI", Font.BOLD, 13);
    private static final Font FONT_SMALL = new Font("Segoe UI", Font.PLAIN, 13);

    private static final String[] CATEGORY_NAMES = { "Physical", "Special", "Status" };

    private static final String[] STATUS_TYPE_NAMES = {
            "None", "Paralyze", "Sleep", "Freeze", "Burn", "Poison", "Confusion", "Toxic Poison"
    };

    private static final String[] STAT_CHANGE_NAMES = {
            "None", "Attack", "Defense", "Sp. Atk", "Sp. Def", "Speed", "Accuracy", "Evasion", "All", "Special"
    };

    private final RomHandler romHandler;
    private final int generation;

    private List<Move> moveList;

    // selectable option sources, built once
    private String[] typeOptions;

    // edit tracking
    private boolean building;
    private final Set<String> editLog = new LinkedHashSet<String>();
    private Runnable saveAction;

    private JTextField searchField;
    private JList<Move> moveJList;
    private DefaultListModel<Move> listModel;
    private JScrollPane cardScrollPane;
    private JPanel cardHolder;
    private JPanel sidebarPanel;
    private JPanel searchRow;
    private JPanel navPanel;
    private JScrollPane listScroll;
    private Move currentMove;

    public MoveCardViewPanel(RomHandler romHandler) {
        this.romHandler = romHandler;
        this.generation = romHandler.generationOfPokemon();
        syncPalette();
        fetchData();
        buildOptionSources();
        initializeUI();
        if (!listModel.isEmpty()) {
            moveJList.setSelectedIndex(0);
        }
        // Re-apply theme + re-render when this tab becomes visible (edits are
        // written live on the Move objects, so they survive a tab switch).
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

    /** Re-reads the live move list from the ROM handler (once, at construction). */
    private void fetchData() {
        moveList = romHandler.getMoves();
    }

    private void buildOptionSources() {
        // Types — respect the loaded gen / fairy mod, like the Moves sheet.
        List<String> types = new ArrayList<String>();
        try {
            for (Type t : romHandler.getTypeTable().getTypes()) {
                types.add(t.name());
            }
        } catch (Exception ignored) {
        }
        typeOptions = types.toArray(new String[0]);
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
        searchField.setToolTipText("Search by name or move number");
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

        listModel = new DefaultListModel<Move>();
        // Move list is 1-based (index 0 is a null placeholder); skip blanks.
        if (moveList != null) {
            for (int i = 1; i < moveList.size(); i++) {
                Move m = moveList.get(i);
                if (m != null && m.name != null && !m.name.trim().isEmpty()) {
                    listModel.addElement(m);
                }
            }
        }
        moveJList = new JList<Move>(listModel);
        moveJList.setCellRenderer(new MoveListRenderer());
        moveJList.setBackground(CARD_BG);
        moveJList.setForeground(TEXT_PRIMARY);
        moveJList.setFixedCellHeight(28);
        moveJList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                Move sel = moveJList.getSelectedValue();
                if (sel != null && sel != currentMove) {
                    showMove(sel);
                }
            }
        });
        listScroll = new JScrollPane(moveJList);
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
        int idx = moveJList.getSelectedIndex();
        int next = idx < 0 ? 0 : Math.min(size - 1, Math.max(0, idx + delta));
        moveJList.setSelectedIndex(next);
        moveJList.ensureIndexIsVisible(next);
    }

    private void filterList() {
        String query = searchField.getText().trim().toLowerCase();
        Move selected = moveJList.getSelectedValue();
        listModel.clear();
        if (moveList != null) {
            for (int i = 1; i < moveList.size(); i++) {
                Move m = moveList.get(i);
                if (m == null || m.name == null || m.name.trim().isEmpty()) {
                    continue;
                }
                if (query.isEmpty()
                        || m.name.toLowerCase().contains(query)
                        || String.valueOf(m.number).equals(query)) {
                    listModel.addElement(m);
                }
            }
        }
        if (selected != null && listModel.contains(selected)) {
            moveJList.setSelectedValue(selected, true);
        } else if (!listModel.isEmpty()) {
            moveJList.setSelectedIndex(0);
        }
    }

    /** Rebuilds the current card after a structural edit, deferred to avoid
     *  modifying the component tree from inside a child component's event. */
    private void rebuild(Move m) {
        SwingUtilities.invokeLater(() -> {
            if (currentMove == m) {
                showMove(m, true); // in-place edit: keep the scroll position
            }
        });
    }

    /** Re-applies the theme palette and redraws the current card (keeps edits). */
    public void refresh() {
        syncPalette();
        applyContainerColors();
        if (currentMove != null) {
            showMove(currentMove, true); // keep scroll position on tab re-show / theme refresh
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
        if (moveJList != null) {
            moveJList.setBackground(CARD_BG);
            moveJList.setForeground(TEXT_PRIMARY);
            moveJList.repaint();
        }
    }

    // ------------------------------------------------------------------
    // Card construction
    // ------------------------------------------------------------------

    private void showMove(Move m) {
        showMove(m, false);
    }

    private void showMove(Move m, boolean preserveScroll) {
        final int previousScroll = preserveScroll
                ? cardScrollPane.getVerticalScrollBar().getValue() : 0;
        building = true;
        currentMove = m;
        cardHolder.removeAll();

        cardHolder.add(buildHeaderSection(m));
        cardHolder.add(Box.createVerticalStrut(10));
        cardHolder.add(buildCoreSection(m));
        cardHolder.add(Box.createVerticalStrut(10));
        if (generation >= 5) {
            cardHolder.add(buildEffectSection(m));
            cardHolder.add(Box.createVerticalStrut(10));
            cardHolder.add(buildStatChangesSection(m));
            cardHolder.add(Box.createVerticalStrut(10));
        }
        cardHolder.add(buildFlagsSection(m));
        if (generation >= 4) {
            cardHolder.add(Box.createVerticalStrut(10));
            cardHolder.add(buildContestSection(m));
        }
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

    private JPanel buildHeaderSection(final Move m) {
        JPanel section = createSection(null);

        JPanel row = new JPanel(new GridBagLayout());
        row.setOpaque(false);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridy = 0;
        gbc.anchor = GridBagConstraints.WEST;

        JPanel nameBlock = new JPanel();
        nameBlock.setOpaque(false);
        nameBlock.setLayout(new BoxLayout(nameBlock, BoxLayout.Y_AXIS));

        JLabel numLabel = new JLabel(String.format("#%03d", m.number));
        numLabel.setFont(new Font("Segoe UI", Font.BOLD, 13));
        numLabel.setForeground(TEXT_MUTED);
        numLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        nameBlock.add(numLabel);

        JLabel nameLabel = new JLabel(m.name != null ? m.name : "?");
        nameLabel.setFont(FONT_TITLE);
        nameLabel.setForeground(TEXT_PRIMARY);
        nameLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        nameBlock.add(nameLabel);

        // Name field + type/category badges
        JPanel editRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        editRow.setOpaque(false);

        // Move names are fixed and are NOT persisted to the ROM here, so show the
        // name display-only — editing it would be a silent no-op and blanking it
        // would drop the move from the list. No commit listener writes m.name.
        JTextField nameField = new JTextField(m.name != null ? m.name : "", 18);
        nameField.setFont(FONT_VALUE);
        nameField.setForeground(TEXT_PRIMARY);
        nameField.setBackground(FIELD_BG);
        nameField.setCaretColor(TEXT_PRIMARY);
        nameField.setEditable(false);
        nameField.setFocusable(false);
        nameField.setToolTipText("Move names are fixed and can't be edited here.");
        editRow.add(taggedControl("Name", nameField));

        JComboBox<String> typeCombo = makeCombo(typeOptions,
                m.type != null ? m.type.name() : "",
                value -> {
                    if (value == null || value.isEmpty()) {
                        return;
                    }
                    m.type = parseType(value);
                    noteEdit(m, "type");
                    rebuild(m);
                });
        editRow.add(taggedControl("Type", typeCombo));

        JComboBox<String> categoryCombo = makeCombo(CATEGORY_NAMES, categoryName(m.category),
                value -> {
                    m.category = parseCategoryName(value);
                    noteEdit(m, "category");
                });
        // Gen3 has no physical/special split — category follows the move's type and
        // isn't persisted — so disable the combo there; editable for Gen4+.
        if (generation == 3) {
            categoryCombo.setEnabled(false);
            categoryCombo.setToolTipText("Gen 3 has no physical/special split — category follows the move's type.");
        }
        editRow.add(taggedControl("Category", categoryCombo));

        if (m.type != null) {
            editRow.add(taggedControl(" ", typeChip(m.type)));
        }

        editRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        nameBlock.add(Box.createVerticalStrut(6));
        nameBlock.add(editRow);

        gbc.gridx = 0;
        gbc.weightx = 1.0;
        row.add(nameBlock, gbc);

        // Save Changes button (top-right)
        JButton saveButton = new JButton("Save Changes");
        saveButton.setFont(FONT_VALUE);
        saveButton.setFocusPainted(false);
        saveButton.setToolTipText("Commit all edits (same as File → Save All)");
        saveButton.addActionListener(e -> saveChanges());
        gbc.gridx = 1;
        gbc.weightx = 0;
        gbc.anchor = GridBagConstraints.NORTHEAST;
        row.add(saveButton, gbc);

        section.add(row);
        return section;
    }

    private JPanel buildCoreSection(final Move m) {
        JPanel section = createSection("Core Stats");
        JPanel grid = infoGrid();
        int[] rowIdx = { 0 };

        addControlRow(grid, rowIdx, "Power", numberCell(m.power, 0, 255,
                v -> { m.power = v; noteEdit(m, "power"); }));
        // Accuracy is a double; round for display, store back as a double.
        // Every gen clamps hitratio on save (Gen3/4 -> 100, Gen5+ -> 101), so cap the
        // editable max to the gen's real maximum to avoid a display/stored desync.
        addControlRow(grid, rowIdx, "Accuracy", numberCell((int) Math.round(m.hitratio), 0, generation <= 4 ? 100 : 101,
                v -> { m.hitratio = v; noteEdit(m, "accuracy"); }));
        addControlRow(grid, rowIdx, "PP", numberCell(m.pp, 0, 255,
                v -> { m.pp = v; noteEdit(m, "pp"); }));
        addControlRow(grid, rowIdx, "Priority", numberCell(m.priority, -8, 8,
                v -> { m.priority = v; noteEdit(m, "priority"); }));
        // Gen3 stores the effect index in a single byte; Gen4+ use 16 bits.
        addControlRow(grid, rowIdx, "Effect Index", numberCell(m.effectIndex, 0, generation == 3 ? 255 : 65535,
                v -> { m.effectIndex = v; noteEdit(m, "effect index"); }));
        // Gen5+ persist this single byte as statusPercentChance (edited via "Status Chance %"
        // in Effect Details), and the two fields share the same ROM byte — so only show the
        // standalone "Secondary Effect %" for Gen3/4, where it is the field that actually saves.
        if (generation <= 4) {
            // Gen3/4 persist this as a full unsigned byte (clamp 0..255 on save), and it
            // doubles as stat-change/status/flinch percent depending on effect — so allow the
            // whole 0..255 range to match the ROM byte width and the Moves Sheet (no upper clamp).
            addControlRow(grid, rowIdx, "Secondary Effect %", numberCell(m.secondaryEffectChance, 0, 255,
                    v -> { m.secondaryEffectChance = v; noteEdit(m, "secondary effect chance"); }));
        }
        // Gen4 stores target as a 16-bit word; Gen3 and Gen5+ store it in a single byte.
        addControlRow(grid, rowIdx, "Target", numberCell(m.target, 0, generation == 4 ? 65535 : 255,
                v -> { m.target = v; noteEdit(m, "target"); }));

        grid.setAlignmentX(Component.LEFT_ALIGNMENT);
        section.add(grid);
        section.add(Box.createVerticalStrut(6));
        section.add(mutedLabel("Advanced category-quality / derived-type fields are edited on the Moves Sheet."));
        return section;
    }

    private JPanel buildEffectSection(final Move m) {
        JPanel section = createSection("Effect Details");
        JPanel grid = infoGrid();
        int[] rowIdx = { 0 };

        addControlRow(grid, rowIdx, "Min Hits", numberCell(m.minHits, 0, 15,
                v -> { m.minHits = v; noteEdit(m, "min hits"); }));
        addControlRow(grid, rowIdx, "Max Hits", numberCell(m.maxHits, 0, 15,
                v -> { m.maxHits = v; noteEdit(m, "max hits"); }));
        addControlRow(grid, rowIdx, "Min Trap Turns", numberCell(m.minTrapTurns, 0, 255,
                v -> { m.minTrapTurns = v; noteEdit(m, "min trap turns"); }));
        addControlRow(grid, rowIdx, "Max Trap Turns", numberCell(m.maxTrapTurns, 0, 255,
                v -> { m.maxTrapTurns = v; noteEdit(m, "max trap turns"); }));
        addControlRow(grid, rowIdx, "Status Type", makeCombo(STATUS_TYPE_NAMES, statusTypeName(m.statusType),
                value -> {
                    StatusType st = parseStatusTypeName(value);
                    if (st != null && st != m.statusType) {
                        m.statusType = st;
                        // Mirror the Moves Sheet: the ROM persists statusEffect, so keep it in sync
                        // (a no-op same-value pick is skipped above so special effect ids aren't clobbered).
                        m.statusEffect = (st == StatusType.TOXIC_POISON) ? StatusType.POISON.ordinal() : st.ordinal();
                    }
                    noteEdit(m, "status type");
                }));
        addControlRow(grid, rowIdx, "Status Chance (0-255)", numberCell((int) Math.round(m.statusPercentChance), 0, 255,
                v -> {
                    m.statusPercentChance = v;
                    m.secondaryEffectChance = v; // same ROM byte — keep in sync (mirrors the Moves Sheet)
                    noteEdit(m, "status chance");
                }));
        addControlRow(grid, rowIdx, "Flinch Chance (0-255)", numberCell((int) Math.round(m.flinchPercentChance), 0, 255,
                v -> { m.flinchPercentChance = v; noteEdit(m, "flinch chance"); }));
        addControlRow(grid, rowIdx, "Crit Stage", numberCell(m.criticalStage, 0, 6,
                v -> {
                    m.criticalStage = v;
                    // Mirror the Moves Sheet: keep the persisted criticalChance enum in sync.
                    m.criticalChance = v >= 6 ? CriticalChance.GUARANTEED
                            : (v > 0 ? CriticalChance.INCREASED : CriticalChance.NORMAL);
                    noteEdit(m, "crit stage");
                }));
        addControlRow(grid, rowIdx, "Recoil / Leech %", numberCell(m.recoilPercent, -128, 127,
                v -> { m.recoilPercent = v; noteEdit(m, "recoil percent"); }));
        addControlRow(grid, rowIdx, "Heal / Absorb %", numberCell(m.absorbPercent, 0, 255,
                v -> { m.absorbPercent = v; noteEdit(m, "absorb percent"); }));

        grid.setAlignmentX(Component.LEFT_ALIGNMENT);
        section.add(grid);
        if (generation == 3) {
            // In Gen 3 every field in this section is DERIVED from the move's Effect Index on load
            // (loadStatusFromEffect / loadMiscMoveInfoFromEffect) and is NOT written back by
            // saveMoves(), so editing them here would silently do nothing. Show them read-only and
            // point the user at the Effect Index field instead.
            setTreeEnabled(grid, false);
            section.add(Box.createVerticalStrut(6));
            section.add(mutedLabel("Gen 3: these fields are derived from the move's Effect Index and are "
                    + "read-only here. Change the Effect Index above to alter them."));
        } else if (generation == 6 || generation == 7) {
            // In Gen6/7 "Recoil / Leech %" and "Heal / Absorb %" can share one ROM byte selected by the
            // move's category-quality, so only the slot matching the move's quality is saved — the other
            // may be silently dropped. Category-quality is edited on the Moves Sheet.
            section.add(Box.createVerticalStrut(6));
            section.add(mutedLabel("Gen 6/7: Recoil and Heal/Absorb can share one ROM byte (chosen by the move's "
                    + "category-quality), so only the matching field is saved — edit category-quality on the Moves Sheet."));
        }
        return section;
    }

    /** Recursively enables/disables a container and all of its descendants (used to show a whole
     *  section read-only when its fields are derived and not persisted for the current generation). */
    private void setTreeEnabled(java.awt.Component c, boolean enabled) {
        c.setEnabled(enabled);
        if (c instanceof java.awt.Container) {
            for (java.awt.Component child : ((java.awt.Container) c).getComponents()) {
                setTreeEnabled(child, enabled);
            }
        }
    }

    private JPanel buildStatChangesSection(final Move m) {
        JPanel section = createSection("Stat Changes");
        JPanel grid = new JPanel(new GridBagLayout());
        grid.setOpaque(false);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(2, 0, 2, 8);
        gbc.anchor = GridBagConstraints.WEST;

        // header row
        gbc.gridy = 0;
        gbc.gridx = 0;
        grid.add(sectionInlineLabel(""), gbc);
        gbc.gridx = 1;
        grid.add(sectionInlineLabel("Stat"), gbc);
        gbc.gridx = 2;
        grid.add(sectionInlineLabel("Stages"), gbc);
        gbc.gridx = 3;
        grid.add(sectionInlineLabel("Chance %"), gbc);

        for (int i = 0; i < 3; i++) {
            final int slot = i;
            final Move.StatChange sc = ensureStatChange(m, i);

            gbc.gridy = i + 1;
            gbc.gridx = 0;
            JLabel rowLabel = new JLabel("#" + (i + 1));
            rowLabel.setFont(FONT_LABEL);
            rowLabel.setForeground(TEXT_MUTED);
            grid.add(rowLabel, gbc);

            gbc.gridx = 1;
            grid.add(makeCombo(STAT_CHANGE_NAMES, statChangeName(sc.type),
                    value -> {
                        ensureStatChange(m, slot).type = parseStatChangeName(value);
                        noteEdit(m, "stat change " + (slot + 1) + " type");
                    }), gbc);

            gbc.gridx = 2;
            grid.add(numberCell(sc.stages, -12, 12,
                    v -> {
                        ensureStatChange(m, slot).stages = v;
                        noteEdit(m, "stat change " + (slot + 1) + " stages");
                    }), gbc);

            gbc.gridx = 3;
            grid.add(numberCell((int) Math.round(sc.percentChance), 0, 255,
                    v -> {
                        ensureStatChange(m, slot).percentChance = v;
                        noteEdit(m, "stat change " + (slot + 1) + " chance");
                    }), gbc);
        }

        grid.setAlignmentX(Component.LEFT_ALIGNMENT);
        section.add(grid);
        return section;
    }

    private JPanel buildFlagsSection(final Move m) {
        JPanel section = createSection("Flags");
        WrapPanel flags = new WrapPanel();

        // Base flags (all gens 3-7)
        flags.add(flagCheck("Makes Contact", m.makesContact,
                v -> { m.makesContact = v; noteEdit(m, "makes contact"); }));
        flags.add(flagCheck("Blocked by Protect", m.isProtectedFromProtect,
                v -> { m.isProtectedFromProtect = v; noteEdit(m, "blocked by protect"); }));
        flags.add(flagCheck("Reflected by Magic Coat", m.isMagicCoatAffected,
                v -> { m.isMagicCoatAffected = v; noteEdit(m, "magic coat"); }));
        flags.add(flagCheck("Affected by Snatch", m.isSnatchAffected,
                v -> { m.isSnatchAffected = v; noteEdit(m, "snatch"); }));
        flags.add(flagCheck("Copied by Mirror Move", m.isMirrorMoveAffected,
                v -> { m.isMirrorMoveAffected = v; noteEdit(m, "mirror move"); }));
        flags.add(flagCheck("Triggers King's Rock / Flinch", m.isFlinchMove,
                v -> { m.isFlinchMove = v; noteEdit(m, "flinch / king's rock"); }));

        if (generation >= 5) {
            flags.add(flagCheck("Charge Move", m.isChargeMove,
                    v -> { m.isChargeMove = v; noteEdit(m, "charge move"); }));
            flags.add(flagCheck("Recharge Move", m.isRechargeMove,
                    v -> { m.isRechargeMove = v; noteEdit(m, "recharge move"); }));
            flags.add(flagCheck("Punch Move", m.isPunchMove,
                    v -> { m.isPunchMove = v; noteEdit(m, "punch move"); }));
            flags.add(flagCheck("Sound Move", m.isSoundMove,
                    v -> { m.isSoundMove = v; noteEdit(m, "sound move"); }));
            flags.add(flagCheck("Grounded by Gravity", m.groundedByGravity,
                    v -> { m.groundedByGravity = v; noteEdit(m, "grounded by gravity"); }));
            flags.add(flagCheck("Defrosts User", m.defrostsUser,
                    v -> { m.defrostsUser = v; noteEdit(m, "defrosts user"); }));
            flags.add(flagCheck("Hits Non-Adjacent", m.hitsNonAdjacent,
                    v -> { m.hitsNonAdjacent = v; noteEdit(m, "hits non-adjacent"); }));
            flags.add(flagCheck("Healing Move", m.isHealingMove,
                    v -> { m.isHealingMove = v; noteEdit(m, "healing move"); }));
            flags.add(flagCheck("Bypasses Substitute", m.bypassesSubstitute,
                    v -> { m.bypassesSubstitute = v; noteEdit(m, "bypasses substitute"); }));
            flags.add(flagCheck("Extra Flag 1", m.extraFlag1,
                    v -> { m.extraFlag1 = v; noteEdit(m, "extra flag 1"); }));
            flags.add(flagCheck("Extra Flag 2", m.extraFlag2,
                    v -> { m.extraFlag2 = v; noteEdit(m, "extra flag 2"); }));
            // "Trap Move" is DERIVED (from Min/Max Trap Turns + effect index) and is NOT persisted
            // on its own, so editing it would be a silent no-op — show it read-only.
            JCheckBox trapBox = flagCheck("Trap Move", m.isTrapMove, v -> { });
            trapBox.setEnabled(false);
            trapBox.setToolTipText("Derived from Min/Max Trap Turns + effect index — not directly editable.");
            flags.add(trapBox);
        }

        // Extended flags: Gen4 (Gen3/4 sheet only shows them for Gen4) and Gen5+.
        if (generation >= 4) {
            flags.add(flagCheck("Hides HP Bars", m.hidesHpBars,
                    v -> { m.hidesHpBars = v; noteEdit(m, "hides HP bars"); }));
            flags.add(flagCheck("Removes Target Shadow", m.removesTargetShadow,
                    v -> { m.removesTargetShadow = v; noteEdit(m, "removes target shadow"); }));
        }

        flags.setAlignmentX(Component.LEFT_ALIGNMENT);
        section.add(flags);
        return section;
    }

    private JPanel buildContestSection(final Move m) {
        JPanel section = createSection("Contest Data");
        JPanel grid = infoGrid();
        int[] rowIdx = { 0 };

        addControlRow(grid, rowIdx, "Contest Effect", numberCell(m.contestEffect, 0, 255,
                v -> { m.contestEffect = v; noteEdit(m, "contest effect"); }));
        addControlRow(grid, rowIdx, "Contest Type", numberCell(m.contestType, 0, 255,
                v -> { m.contestType = v; noteEdit(m, "contest type"); }));

        grid.setAlignmentX(Component.LEFT_ALIGNMENT);
        section.add(grid);
        return section;
    }

    private Move.StatChange ensureStatChange(Move m, int index) {
        if (m.statChanges[index] == null) {
            m.statChanges[index] = new Move.StatChange();
            m.statChanges[index].type = StatChangeType.NONE;
        }
        return m.statChanges[index];
    }

    // ------------------------------------------------------------------
    // Save / persistence
    // ------------------------------------------------------------------

    private void saveChanges() {
        if (saveAction != null) {
            saveAction.run(); // frame.saveAll() — commits sheets + Moves, calls our save() last
        } else {
            save();
        }
    }

    /**
     * Called by the editor frame's Save All. Move edits are written live on the
     * Move objects (the main-window ROM save reads them), so there is no setter
     * to call here — we only record the edit log.
     */
    public void save() {
        commitFocus();
        if (!editLog.isEmpty()) {
            ManualEditRegistry.getInstance().addEntries("Move Card", new ArrayList<String>(editLog));
            editLog.clear();
        }
    }

    /** Read-only card lifecycle: nothing to revert here (Move reverts are handled by the sheets). */
    public void onWindowClosing() {
        // no-op
    }

    /** Forces any focused number field to commit before saving. */
    private void commitFocus() {
        Component focus = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        if (focus instanceof JTextField) {
            ((JTextField) focus).postActionEvent(); // commit synchronously so menu Save All doesn't drop a typed value
        }
    }

    private void noteEdit(Move m, String what) {
        if (building) {
            return;
        }
        editLog.add(m.number + " " + m.name + ": " + what);
    }

    // ------------------------------------------------------------------
    // Editable control builders
    // ------------------------------------------------------------------

    private JComboBox<String> makeCombo(String[] items, String current, Consumer<String> onSelect) {
        JComboBox<String> combo = new JComboBox<String>(items);
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
                onSelect.accept(sel.toString());
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

    private JCheckBox flagCheck(String label, boolean selected, Consumer<Boolean> onToggle) {
        final JCheckBox box = new JCheckBox(label, selected);
        box.setOpaque(false);
        box.setForeground(TEXT_PRIMARY);
        box.setFont(FONT_LABEL);
        box.addActionListener(e -> {
            if (!building) {
                onToggle.accept(box.isSelected());
            }
        });
        return box;
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

    /** Small caption above a control. */
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

    private static String categoryName(MoveCategory category) {
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

    private static MoveCategory parseCategoryName(String name) {
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

    private static String statusTypeName(StatusType type) {
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

    private static StatusType parseStatusTypeName(String name) {
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

    private static String statChangeName(StatChangeType type) {
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

    private static StatChangeType parseStatChangeName(String name) {
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

    private static JComponent typeChip(Type type) {
        if (type == null) {
            return chip("None", CHIP_GRAY, TEXT_MUTED);
        }
        return chip(prettifyEnumName(type.name()).toUpperCase(), typeColor(type), Color.WHITE);
    }

    /** Standard type colors, matching the Moves Sheet renderer. */
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

    private class MoveListRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                boolean isSelected, boolean cellHasFocus) {
            JLabel label = (JLabel) super.getListCellRendererComponent(
                    list, value, index, isSelected, cellHasFocus);
            if (value instanceof Move) {
                Move m = (Move) value;
                label.setText(String.format("#%03d  %s", m.number, m.name != null ? m.name : "?"));
                label.setFont(FONT_LABEL);
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
