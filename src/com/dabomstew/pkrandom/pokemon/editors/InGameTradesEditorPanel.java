package com.dabomstew.pkrandom.pokemon.editors;

import com.dabomstew.pkrandom.log.ManualEditRegistry;
import com.dabomstew.pkromio.gamedata.InGameTrade;
import com.dabomstew.pkromio.gamedata.Item;
import com.dabomstew.pkromio.gamedata.Species;
import com.dabomstew.pkromio.romhandlers.RomHandler;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.AbstractDocument;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import javax.swing.text.DocumentFilter;
import java.awt.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * In-Game Trades editor: pick an NPC trade on the left, edit its core properties
 * on the right. Generation-agnostic — everything is driven through the
 * {@link RomHandler} interface ({@code getInGameTrades} / {@code setInGameTrades}),
 * so the single panel is reused by every generation that exposes trade data.
 *
 * <p>Persistence model (mirrors {@link StaticEncountersEditorPanel}, the safest of
 * the surrounding patterns): the panel fetches the trade list once and edits only
 * deep copies. {@link InGameTrade} has no copy constructor, so each copy is built
 * field-by-field with the {@code ivs} array cloned. The live handler data is never
 * touched until {@link #save()} pushes the working copies via
 * {@code setInGameTrades(...)}. Closing without saving therefore leaks nothing —
 * there is nothing to revert because the handler was never mutated.</p>
 *
 * <p>Field handling notes:
 * <ul>
 *   <li><b>Requested species</b> may be null, meaning "any Pokémon". A leading
 *       "(Any)" combo entry maps to/from null and round-trips faithfully.</li>
 *   <li><b>Given species</b> is never nulled: an empty/unknown combo keeps the
 *       slot's original species.</li>
 *   <li><b>Held item</b> is resolved by id (not name) and may be "None"/null.</li>
 *   <li><b>OT ID</b> is clamped to 0–65535.</li>
 *   <li><b>Nickname / OT Name</b> text is capped via a {@link DocumentFilter} to a
 *       conservative length so an over-long string can't overflow the fixed-length
 *       ROM buffer. The exact per-gen maxima are not exposed by the handler, so the
 *       caps here (10 for nickname, 7 for OT name) are deliberately conservative and
 *       warrant per-generation verification.</li>
 *   <li><b>IVs</b> are shown read-only: the {@code ivs} array varies in length and
 *       meaning per generation, so it is displayed but preserved verbatim.</li>
 * </ul></p>
 */
public class InGameTradesEditorPanel extends JPanel {

    private static final Font FONT_LABEL = new Font("Segoe UI", Font.PLAIN, 12);
    private static final Font FONT_VALUE = new Font("Segoe UI", Font.BOLD, 12);

    // Conservative caps on the fixed-length ROM string fields. The handler does not
    // expose the true per-gen limits, so these are intentionally short to avoid
    // overflowing the buffer; see class javadoc (needs per-gen verification).
    private static final int MAX_NICKNAME = 10;
    private static final int MAX_OT_NAME = 7;

    private static final int OT_ID_MAX = 65535;

    private final RomHandler romHandler;
    private final List<Species> speciesList;       // getSpeciesInclFormes(), index-stable
    private final List<Item> itemList;             // getItems(), id-keyed identity (may contain nulls)
    private final String[] givenOptions;           // species options (no "(Any)")
    private final String[] requestedOptions;       // species options with a leading "(Any)" -> null
    private final Map<String, Species> speciesByOption = new LinkedHashMap<String, Species>();
    private final String[] itemOptions;            // "<id>: <name>" (or "None") for the combo

    private List<InGameTrade> working;             // deep copies; only these are edited

    private JList<TradeEntry> tradeJList;
    private DefaultListModel<TradeEntry> tradeListModel;
    private JTextField searchField;

    private JLabel titleLabel;
    private JLabel infoLabel;
    private JComboBox<String> givenCombo;
    private JComboBox<String> requestedCombo;
    private JTextField nicknameField;
    private JTextField otNameField;
    private JSpinner otIdSpinner;
    private JComboBox<String> heldItemCombo;
    private JLabel ivsLabel;

    private InGameTrade currentTrade;
    private boolean building;                       // suppress listener writes while (re)building the form

    private Runnable saveAction;

    private final java.util.Set<String> editLog = new LinkedHashSet<String>();

    public InGameTradesEditorPanel(RomHandler romHandler) {
        this.romHandler = romHandler;
        this.speciesList = romHandler.getSpeciesInclFormes();
        this.itemList = romHandler.getItems();

        List<InGameTrade> original;
        try {
            original = romHandler.getInGameTrades();
        } catch (Exception e) {
            original = new ArrayList<InGameTrade>();
        }
        this.working = deepCopy(original);

        this.givenOptions = buildSpeciesOptions(false);
        this.requestedOptions = buildSpeciesOptions(true);
        this.itemOptions = buildItemOptions();
        initUI();
        if (!tradeListModel.isEmpty()) {
            tradeJList.setSelectedIndex(0);
        } else {
            showTrade(null);
        }
    }

    public void setSaveAction(Runnable saveAction) {
        this.saveAction = saveAction;
    }

    // ------------------------------------------------------------------
    // Setup
    // ------------------------------------------------------------------

    /**
     * Builds the shared species option list. When {@code withAny} is true a leading
     * "(Any)" entry is prepended (used by the requested-species combo to mean null).
     * The {@code speciesByOption} map is populated once (it is identical for both),
     * resolving each label back to its Species.
     */
    private String[] buildSpeciesOptions(boolean withAny) {
        List<String> opts = new ArrayList<String>();
        if (withAny) {
            opts.add(ANY_OPTION);
        }
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

    private static final String ANY_OPTION = "(Any)";

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

    /**
     * Deep-copies the trade list. {@link InGameTrade} has no copy constructor, so
     * each copy is built field-by-field and the {@code ivs} array is cloned so the
     * editor never aliases the handler's arrays.
     */
    private static List<InGameTrade> deepCopy(List<InGameTrade> src) {
        List<InGameTrade> out = new ArrayList<InGameTrade>(src == null ? 0 : src.size());
        if (src == null) {
            return out;
        }
        for (InGameTrade t : src) {
            out.add(copyTrade(t));
        }
        return out;
    }

    private static InGameTrade copyTrade(InGameTrade t) {
        if (t == null) {
            return null;
        }
        InGameTrade c = new InGameTrade();
        c.setRequestedSpecies(t.getRequestedSpecies()); // may be null = "any"
        c.setGivenSpecies(t.getGivenSpecies());
        c.setNickname(t.getNickname());
        c.setOtName(t.getOtName());
        c.setOtId(t.getOtId());
        int[] ivs = t.getIVs();
        c.setIVs(ivs == null ? new int[0] : ivs.clone());
        c.setHeldItem(t.getHeldItem());
        return c;
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

        JLabel info = new JLabel(String.format("In-Game Trades - %d %s",
                working.size(), working.size() == 1 ? "trade" : "trades"));
        info.setFont(FONT_LABEL);
        info.setForeground(EditorTheme.mutedText());
        toolbar.add(info);

        return toolbar;
    }

    private JPanel buildSidebar() {
        JPanel sidebar = new JPanel(new BorderLayout(0, 6));
        sidebar.setPreferredSize(new Dimension(320, 10));
        sidebar.setBackground(EditorTheme.surface());
        sidebar.setBorder(new EmptyBorder(12, 12, 12, 0));

        searchField = new JTextField();
        searchField.setFont(FONT_LABEL);
        searchField.setToolTipText("Filter trades by species");
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

        tradeListModel = new DefaultListModel<TradeEntry>();
        for (int i = 0; i < working.size(); i++) {
            InGameTrade t = working.get(i);
            if (t != null) {
                tradeListModel.addElement(new TradeEntry(t, i));
            }
        }
        tradeJList = new JList<TradeEntry>(tradeListModel);
        tradeJList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        tradeJList.setBackground(EditorTheme.surface());
        tradeJList.setForeground(EditorTheme.text());
        tradeJList.setFont(FONT_LABEL);
        tradeJList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                TradeEntry sel = tradeJList.getSelectedValue();
                if (sel != null && sel.trade != currentTrade) {
                    showTrade(sel.trade);
                }
            }
        });
        JScrollPane listScroll = new JScrollPane(tradeJList);
        listScroll.setBorder(BorderFactory.createLineBorder(EditorTheme.border()));
        listScroll.getVerticalScrollBar().setUnitIncrement(16);
        sidebar.add(listScroll, BorderLayout.CENTER);

        return sidebar;
    }

    private JPanel buildDetail() {
        JPanel detail = new JPanel(new BorderLayout(0, 8));
        detail.setBackground(EditorTheme.surface());
        detail.setBorder(new EmptyBorder(12, 12, 12, 12));

        // Header: trade summary + info line
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
        // showTrade() repopulates them under the 'building' guard.
        JPanel formPanel = new JPanel(new GridBagLayout());
        formPanel.setOpaque(false);
        formPanel.setBorder(new EmptyBorder(8, 0, 0, 0));
        int[] row = { 0 };

        givenCombo = new JComboBox<String>(givenOptions);
        givenCombo.setFont(FONT_VALUE);
        givenCombo.setForeground(EditorTheme.text());
        givenCombo.setBackground(EditorTheme.surface());
        EditorUtils.installSearchableComboBox(givenCombo);
        givenCombo.addActionListener(e -> {
            if (building || currentTrade == null) {
                return;
            }
            Species picked = speciesForOption((String) givenCombo.getSelectedItem());
            // Tolerate a null pick: never null the given species — the game expects
            // a Pokémon to hand over. Keep the existing one if the combo is empty.
            if (picked != null && picked != currentTrade.getGivenSpecies()) {
                currentTrade.setGivenSpecies(picked);
                note(currentTrade, "given species -> " + picked.getName());
                refreshCurrentRow();
            }
        });
        addRow(formPanel, row, "Given Species", givenCombo);

        requestedCombo = new JComboBox<String>(requestedOptions);
        requestedCombo.setFont(FONT_VALUE);
        requestedCombo.setForeground(EditorTheme.text());
        requestedCombo.setBackground(EditorTheme.surface());
        EditorUtils.installSearchableComboBox(requestedCombo);
        requestedCombo.addActionListener(e -> {
            if (building || currentTrade == null) {
                return;
            }
            String opt = (String) requestedCombo.getSelectedItem();
            // "(Any)" maps to null (= "any Pokémon"); any species maps to itself.
            Species picked = ANY_OPTION.equals(opt) ? null : speciesForOption(opt);
            if (!sameSpecies(picked, currentTrade.getRequestedSpecies())) {
                currentTrade.setRequestedSpecies(picked);
                note(currentTrade, "requested species -> " + (picked == null ? "(Any)" : picked.getName()));
                refreshCurrentRow();
            }
        });
        addRow(formPanel, row, "Requested Species", requestedCombo);

        nicknameField = new JTextField();
        nicknameField.setFont(FONT_VALUE);
        nicknameField.setColumns(16);
        installLengthFilter(nicknameField, MAX_NICKNAME);
        nicknameField.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { nicknameChanged(); }
            public void removeUpdate(DocumentEvent e) { nicknameChanged(); }
            public void changedUpdate(DocumentEvent e) { nicknameChanged(); }
        });
        addRow(formPanel, row, "Nickname", nicknameField);

        otNameField = new JTextField();
        otNameField.setFont(FONT_VALUE);
        otNameField.setColumns(16);
        installLengthFilter(otNameField, MAX_OT_NAME);
        otNameField.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { otNameChanged(); }
            public void removeUpdate(DocumentEvent e) { otNameChanged(); }
            public void changedUpdate(DocumentEvent e) { otNameChanged(); }
        });
        addRow(formPanel, row, "OT Name", otNameField);

        otIdSpinner = new JSpinner(new SpinnerNumberModel(0, 0, OT_ID_MAX, 1));
        otIdSpinner.setFont(FONT_VALUE);
        ((JSpinner.DefaultEditor) otIdSpinner.getEditor()).getTextField().setColumns(6);
        otIdSpinner.addChangeListener(e -> {
            if (building || currentTrade == null) {
                return;
            }
            int v = clamp((Integer) otIdSpinner.getValue(), 0, OT_ID_MAX);
            if (v != currentTrade.getOtId()) {
                currentTrade.setOtId(v);
                note(currentTrade, "OT ID -> " + v);
            }
        });
        addRow(formPanel, row, "OT ID", otIdSpinner);

        heldItemCombo = new JComboBox<String>(itemOptions);
        heldItemCombo.setFont(FONT_VALUE);
        heldItemCombo.setForeground(EditorTheme.text());
        heldItemCombo.setBackground(EditorTheme.surface());
        EditorUtils.installSearchableComboBox(heldItemCombo);
        heldItemCombo.addActionListener(e -> {
            if (building || currentTrade == null) {
                return;
            }
            Item item = itemForOption((String) heldItemCombo.getSelectedItem());
            if (!sameItem(item, currentTrade.getHeldItem())) {
                currentTrade.setHeldItem(item);
                note(currentTrade, "held item -> " + (item == null ? "(None)" : item.getName()));
            }
        });
        addRow(formPanel, row, "Held Item", heldItemCombo);

        ivsLabel = new JLabel(" ");
        ivsLabel.setFont(FONT_VALUE);
        ivsLabel.setForeground(EditorTheme.text());
        addRow(formPanel, row, "IVs", ivsLabel);

        // Keep the form pinned to the top-left (don't stretch controls vertically).
        JPanel formHolder = new JPanel(new BorderLayout());
        formHolder.setOpaque(false);
        formHolder.add(formPanel, BorderLayout.NORTH);
        detail.add(formHolder, BorderLayout.CENTER);

        JLabel hint = new JLabel("Edits apply to a working copy; click Save (or File -> Save All) to keep them. "
                + "IVs are read-only; Nickname/OT Name lengths are capped to fit the ROM.");
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
        l.setPreferredSize(new Dimension(120, 24));
        grid.add(l, gc);
        gc.gridx = 1;
        gc.weightx = 1.0;
        grid.add(control, gc);
        r[0]++;
    }

    /**
     * Caps a text field to {@code max} characters via a {@link DocumentFilter} so an
     * over-long nickname / OT name can never overflow the fixed-length ROM buffer.
     * The filter trims pasted text to fit rather than rejecting the whole paste.
     */
    private static void installLengthFilter(JTextField field, int max) {
        Document doc = field.getDocument();
        if (doc instanceof AbstractDocument) {
            ((AbstractDocument) doc).setDocumentFilter(new MaxLengthFilter(max));
        }
    }

    // ------------------------------------------------------------------
    // Field change handlers (text fields)
    // ------------------------------------------------------------------

    private void nicknameChanged() {
        if (building || currentTrade == null) {
            return;
        }
        String v = nicknameField.getText();
        if (!equalsString(v, currentTrade.getNickname())) {
            currentTrade.setNickname(v);
            note(currentTrade, "nickname -> " + v);
            refreshCurrentRow();
        }
    }

    private void otNameChanged() {
        if (building || currentTrade == null) {
            return;
        }
        String v = otNameField.getText();
        if (!equalsString(v, currentTrade.getOtName())) {
            currentTrade.setOtName(v);
            note(currentTrade, "OT name -> " + v);
        }
    }

    // ------------------------------------------------------------------
    // Selection / display
    // ------------------------------------------------------------------

    private void filterList() {
        String q = searchField.getText().trim().toLowerCase();
        TradeEntry selected = tradeJList.getSelectedValue();
        tradeListModel.clear();
        for (int i = 0; i < working.size(); i++) {
            InGameTrade t = working.get(i);
            if (t == null) {
                continue;
            }
            TradeEntry entry = new TradeEntry(t, i);
            if (q.isEmpty() || entry.toString().toLowerCase().contains(q)) {
                tradeListModel.addElement(entry);
            }
        }
        if (selected != null) {
            for (int i = 0; i < tradeListModel.size(); i++) {
                if (tradeListModel.get(i).trade == selected.trade) {
                    tradeJList.setSelectedIndex(i);
                    return;
                }
            }
        }
        if (!tradeListModel.isEmpty()) {
            tradeJList.setSelectedIndex(0);
        }
    }

    private void showTrade(InGameTrade t) {
        currentTrade = t;
        building = true;
        try {
            boolean enabled = t != null;
            givenCombo.setEnabled(enabled);
            requestedCombo.setEnabled(enabled);
            nicknameField.setEnabled(enabled);
            otNameField.setEnabled(enabled);
            otIdSpinner.setEnabled(enabled);
            heldItemCombo.setEnabled(enabled);

            if (t == null) {
                titleLabel.setText("(no trade selected)");
                infoLabel.setText(" ");
                if (givenOptions.length > 0) {
                    givenCombo.setSelectedItem(givenOptions[0]);
                }
                requestedCombo.setSelectedItem(ANY_OPTION);
                nicknameField.setText("");
                otNameField.setText("");
                otIdSpinner.setValue(0);
                heldItemCombo.setSelectedItem("None");
                ivsLabel.setText(" ");
                return;
            }

            titleLabel.setText(tradeSummary(t));

            StringBuilder info = new StringBuilder();
            info.append("#").append(indexOfTrade(t) + 1);
            infoLabel.setText(info.toString());

            givenCombo.setSelectedItem(optionForSpecies(t.getGivenSpecies()));
            requestedCombo.setSelectedItem(
                    t.getRequestedSpecies() == null ? ANY_OPTION : optionForSpecies(t.getRequestedSpecies()));
            nicknameField.setText(t.getNickname() == null ? "" : t.getNickname());
            otNameField.setText(t.getOtName() == null ? "" : t.getOtName());
            otIdSpinner.setValue(clamp(t.getOtId(), 0, OT_ID_MAX));
            heldItemCombo.setSelectedItem(optionForItem(t.getHeldItem()));
            ivsLabel.setText(formatIVs(t.getIVs()));
        } finally {
            building = false;
        }
    }

    /** Refresh the sidebar label + header for the current trade. */
    private void refreshCurrentRow() {
        if (currentTrade == null) {
            return;
        }
        titleLabel.setText(tradeSummary(currentTrade));
        int idx = tradeJList.getSelectedIndex();
        if (idx >= 0) {
            tradeListModel.set(idx, new TradeEntry(currentTrade, indexOfTrade(currentTrade)));
            // Re-selecting fires a (non-adjusting) event, but currentTrade is unchanged
            // so showTrade() is skipped by the listener's identity check.
            tradeJList.setSelectedIndex(idx);
        }
    }

    private int indexOfTrade(InGameTrade t) {
        for (int i = 0; i < working.size(); i++) {
            if (working.get(i) == t) {
                return i;
            }
        }
        return -1;
    }

    private static String tradeSummary(InGameTrade t) {
        String given = t.getGivenSpecies() == null ? "(none)" : t.getGivenSpecies().getName();
        String want = t.getRequestedSpecies() == null ? "(Any)" : t.getRequestedSpecies().getName();
        return "Give " + given + "  /  Want " + want;
    }

    private static String formatIVs(int[] ivs) {
        if (ivs == null || ivs.length == 0) {
            return "(none)";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ivs.length; i++) {
            if (i > 0) {
                sb.append("/");
            }
            sb.append(ivs[i]);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Save / persistence
    // ------------------------------------------------------------------

    public void save() {
        boolean ok = true;
        try {
            romHandler.setInGameTrades(working);
        } catch (Exception e) {
            e.printStackTrace();
            ok = false;
            JOptionPane.showMessageDialog(this,
                    "Failed to save in-game trades:\n" + e.getMessage(),
                    "Save Failed",
                    JOptionPane.ERROR_MESSAGE);
        }
        if (!ok) {
            return;
        }
        if (!editLog.isEmpty()) {
            ManualEditRegistry.getInstance().addEntries("In-Game Trades", new ArrayList<String>(editLog));
            editLog.clear();
        }
        if (!EditorUtils.suppressSaveDialogs) {
            JOptionPane.showMessageDialog(this,
                    "In-game trades updated!\n\nChanges will be saved when you save/randomize the ROM.",
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

    private void note(InGameTrade t, String what) {
        editLog.add("#" + (indexOfTrade(t) + 1) + " " + tradeSummary(t) + ": " + what);
    }

    // ------------------------------------------------------------------
    // Option <-> model resolution (species & items resolved by id, not name)
    // ------------------------------------------------------------------

    private Species speciesForOption(String option) {
        if (option == null) {
            return null;
        }
        return speciesByOption.get(option); // null for "(Any)" or unknown
    }

    private String optionForSpecies(Species s) {
        if (s == null) {
            // Used for the given combo only (requested handles null as "(Any)"
            // before calling this); fall back to the first option.
            return givenOptions.length > 0 ? givenOptions[0] : "";
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

    private static boolean sameSpecies(Species a, Species b) {
        return a == b; // species are identity-stable singletons from getSpeciesInclFormes()
    }

    private static boolean equalsString(String a, String b) {
        return a == null ? b == null : a.equals(b);
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
    // Models / helpers
    // ------------------------------------------------------------------

    /** Sidebar list entry pairing a trade with its original index. */
    private static final class TradeEntry {
        final InGameTrade trade;
        final int index;

        TradeEntry(InGameTrade trade, int index) {
            this.trade = trade;
            this.index = index;
        }

        @Override
        public String toString() {
            String given = trade.getGivenSpecies() == null ? "(none)" : trade.getGivenSpecies().getName();
            String want = trade.getRequestedSpecies() == null ? "(Any)" : trade.getRequestedSpecies().getName();
            return "#" + (index + 1) + "  give " + given + " / want " + want;
        }
    }

    /**
     * Caps inserts/replacements so the document never exceeds {@code max} characters.
     * Over-long text is trimmed to fit (rather than rejected wholesale) so a long
     * paste still lands its leading characters.
     */
    private static final class MaxLengthFilter extends DocumentFilter {
        private final int max;

        MaxLengthFilter(int max) {
            this.max = Math.max(0, max);
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
            String toInsert = string.length() > room ? string.substring(0, room) : string;
            super.insertString(fb, offset, toInsert, attr);
        }

        @Override
        public void replace(FilterBypass fb, int offset, int length, String text, AttributeSet attrs)
                throws BadLocationException {
            if (text == null) {
                text = "";
            }
            int currentLength = fb.getDocument().getLength();
            int room = max - (currentLength - length);
            if (room <= 0) {
                // No room after the replaced span is removed; just delete the span.
                super.replace(fb, offset, length, "", attrs);
                return;
            }
            String toInsert = text.length() > room ? text.substring(0, room) : text;
            super.replace(fb, offset, length, toInsert, attrs);
        }
    }
}
