package com.dabomstew.pkrandom.pokemon.editors;

import com.dabomstew.pkrandom.log.ManualEditRegistry;
import com.dabomstew.pkromio.gamedata.Item;
import com.dabomstew.pkromio.romhandlers.RomHandler;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableColumn;
import javax.swing.table.TableColumnModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Field-Item editor: one scrollable table with a row per overworld / hidden
 * field-item slot. Generation-agnostic — everything is driven through the
 * {@link RomHandler} interface ({@code getFieldItems} / {@code setFieldItems}),
 * so the single panel is reused by every generation that exposes field items.
 *
 * <p><b>The TM-ness invariant.</b> {@code setFieldItems} requires that the
 * TM-ness at each index of the list passed in matches the TM-ness at that index
 * in {@code getFieldItems()} (a TM slot must stay a TM, a regular slot must stay
 * a regular item) — violating it throws and corrupts the ROM. This panel anchors
 * the invariant to the <em>original</em> list: it records {@code slotIsTM[i]} at
 * construction, and each row's combo only ever offers items of that slot's
 * TM-ness (TM slots get the TM pool; regular slots get the sorted non-TM allowed
 * pool). The invariant is therefore preserved by construction, and {@link #save()}
 * additionally guards it — any row whose selection somehow flipped TM-ness is
 * reverted to its original item and noted.</p>
 *
 * <p>Persistence model (same as the sibling editors): the panel fetches the
 * field-item list once and edits only a private copy. The live handler data is
 * untouched until {@link #save()} pushes the working copy via
 * {@code setFieldItems(...)}. Closing without saving leaks nothing — there is
 * nothing to revert because the handler was never mutated.</p>
 */
public class FieldItemsEditorPanel extends JPanel {

    private static final Font FONT_LABEL = new Font("Segoe UI", Font.PLAIN, 12);
    private static final Font FONT_VALUE = new Font("Segoe UI", Font.BOLD, 12);

    private final RomHandler romHandler;

    // The slots being edited. working.get(i) is the current pick for slot i; it is
    // initialised from a copy of getFieldItems() and only ever reassigned to an
    // Item of the same TM-ness as slotIsTM[i].
    private final List<Item> working;
    // Pristine snapshot of getFieldItems(), used as the invariant anchor and as
    // the safe fallback for a row that ends up with no valid selection.
    private final List<Item> original;
    // TM-ness of each slot as read from the ORIGINAL list. This is the contract
    // setFieldItems checks against, so it never changes once captured.
    private final boolean[] slotIsTM;

    // Combo option strings ("<id>: <name>") -> Item, split by TM-ness. The "byId"
    // maps let us resolve a slot's current item back to its display option.
    private final String[] tmOptions;
    private final String[] nonTMOptions;
    private final Map<String, Item> tmByOption = new LinkedHashMap<String, Item>();
    private final Map<String, Item> nonTMByOption = new LinkedHashMap<String, Item>();
    private final Map<Integer, String> tmOptionById = new LinkedHashMap<Integer, String>();
    private final Map<Integer, String> nonTMOptionById = new LinkedHashMap<Integer, String>();

    private JTable table;
    private SlotTableModel model;
    private Runnable saveAction;

    private final java.util.Set<String> editLog = new LinkedHashSet<String>();

    public FieldItemsEditorPanel(RomHandler romHandler) {
        this.romHandler = romHandler;

        List<Item> fetched;
        try {
            fetched = romHandler.getFieldItems();
        } catch (Exception e) {
            fetched = new ArrayList<Item>();
        }
        if (fetched == null) {
            fetched = new ArrayList<Item>();
        }

        this.original = new ArrayList<Item>(fetched);
        this.working = new ArrayList<Item>(fetched);
        this.slotIsTM = new boolean[original.size()];
        for (int i = 0; i < original.size(); i++) {
            Item it = original.get(i);
            slotIsTM[i] = it != null && it.isTM();
        }

        // Build the two item pools mirroring ItemRandomizer.randomizeFieldItems():
        //  - TM slots:      getItems() filtered by isTM()
        //  - non-TM slots:  getAllowedItems() minus TMs
        // Both sorted by id, and labelled "<id>: <name>".
        List<Item> tmPool = buildTMPool();
        List<Item> nonTMPool = buildNonTMPool();
        this.tmOptions = buildOptions(tmPool, tmByOption, tmOptionById);
        this.nonTMOptions = buildOptions(nonTMPool, nonTMByOption, nonTMOptionById);

        initUI();
    }

    public void setSaveAction(Runnable saveAction) {
        this.saveAction = saveAction;
    }

    // ------------------------------------------------------------------
    // Pools
    // ------------------------------------------------------------------

    private List<Item> buildTMPool() {
        List<Item> pool = new ArrayList<Item>();
        try {
            for (Item it : romHandler.getItems()) {
                if (it != null && it.isTM()) {
                    pool.add(it);
                }
            }
        } catch (Exception ignored) {
        }
        // Make sure every TM that's currently sitting in a slot is selectable,
        // even in the unlikely event it isn't returned by getItems().
        ensureCurrentItemsPresent(pool, true);
        sortById(pool);
        return pool;
    }

    private List<Item> buildNonTMPool() {
        List<Item> pool = new ArrayList<Item>();
        try {
            for (Item it : romHandler.getAllowedItems()) {
                if (it != null && !it.isTM()) {
                    pool.add(it);
                }
            }
        } catch (Exception ignored) {
        }
        // Guarantee a slot's current regular item is always offered, even if it
        // isn't in the allowed set (so we never silently drop a vanilla item).
        ensureCurrentItemsPresent(pool, false);
        sortById(pool);
        return pool;
    }

    /** Adds any current slot item of the requested TM-ness that's missing from {@code pool}. */
    private void ensureCurrentItemsPresent(List<Item> pool, boolean wantTM) {
        for (int i = 0; i < original.size(); i++) {
            Item it = original.get(i);
            if (it == null) {
                continue;
            }
            if (it.isTM() == wantTM && !pool.contains(it)) {
                pool.add(it);
            }
        }
    }

    private static void sortById(List<Item> items) {
        items.sort(new java.util.Comparator<Item>() {
            @Override
            public int compare(Item a, Item b) {
                return Integer.compare(a.getId(), b.getId());
            }
        });
    }

    private static String optionLabel(Item it) {
        return it.getId() + ": " + it.getName();
    }

    private static String[] buildOptions(List<Item> pool, Map<String, Item> byOption,
            Map<Integer, String> optionById) {
        List<String> opts = new ArrayList<String>(pool.size());
        for (Item it : pool) {
            String label = optionLabel(it);
            // De-dup defensively (two items shouldn't share id+name, but be safe).
            if (byOption.containsKey(label)) {
                label = label + "  [#" + it.getId() + "]";
            }
            byOption.put(label, it);
            optionById.put(it.getId(), label);
            opts.add(label);
        }
        return opts.toArray(new String[0]);
    }

    // ------------------------------------------------------------------
    // Setup
    // ------------------------------------------------------------------

    private void initUI() {
        setLayout(new BorderLayout());
        setBackground(EditorTheme.surface());

        add(buildToolbar(), BorderLayout.NORTH);
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

        int tmCount = 0;
        for (boolean isTM : slotIsTM) {
            if (isTM) {
                tmCount++;
            }
        }
        JLabel infoLabel = new JLabel(String.format(
                "Field Items - %d slots (%d TM, %d regular)",
                working.size(), tmCount, working.size() - tmCount));
        infoLabel.setFont(FONT_LABEL);
        infoLabel.setForeground(EditorTheme.mutedText());
        toolbar.add(infoLabel);

        return toolbar;
    }

    private JPanel buildDetail() {
        JPanel detail = new JPanel(new BorderLayout(0, 8));
        detail.setBackground(EditorTheme.surface());
        detail.setBorder(new EmptyBorder(12, 12, 12, 12));

        model = new SlotTableModel();
        table = new JTable(model);
        TableLayoutDefaults.applyBaseTableSettings(table);
        TableLayoutDefaults.installStripedRenderers(table, false);
        table.setRowHeight(26);
        table.getTableHeader().setReorderingAllowed(false);
        table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        configureColumns();

        JScrollPane tableScroll = new JScrollPane(table);
        tableScroll.setBorder(BorderFactory.createLineBorder(EditorTheme.border()));
        tableScroll.getViewport().setBackground(EditorTheme.surface());
        tableScroll.getVerticalScrollBar().setUnitIncrement(16);
        detail.add(tableScroll, BorderLayout.CENTER);

        JLabel hint = new JLabel("Each slot keeps its type: TM slots only offer TMs, regular slots only offer "
                + "regular items. Edits apply to a working copy; click Save (or File -> Save All) to keep them.");
        hint.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        hint.setForeground(EditorTheme.mutedText());
        detail.add(hint, BorderLayout.SOUTH);

        return detail;
    }

    private void configureColumns() {
        TableColumnModel cm = table.getColumnModel();
        // 0 = #, 1 = TM?, 2 = Item
        cm.getColumn(0).setPreferredWidth(50);
        cm.getColumn(0).setMaxWidth(70);

        cm.getColumn(1).setPreferredWidth(60);
        cm.getColumn(1).setMaxWidth(80);

        TableColumn itemCol = cm.getColumn(2);
        itemCol.setPreferredWidth(320);
        // One combo per TM-ness; pick the right one at edit time so a row can only
        // ever resolve to an item that preserves that slot's TM-ness.
        final JComboBox<String> tmCombo = new JComboBox<String>(tmOptions);
        tmCombo.setFont(FONT_VALUE);
        EditorUtils.installSearchableComboBox(tmCombo);
        final JComboBox<String> nonTMCombo = new JComboBox<String>(nonTMOptions);
        nonTMCombo.setFont(FONT_VALUE);
        EditorUtils.installSearchableComboBox(nonTMCombo);

        final DefaultCellEditor tmEditor = new DefaultCellEditor(tmCombo);
        final DefaultCellEditor nonTMEditor = new DefaultCellEditor(nonTMCombo);

        itemCol.setCellEditor(new ItemCellEditor(tmEditor, nonTMEditor));
    }

    // ------------------------------------------------------------------
    // Save / persistence
    // ------------------------------------------------------------------

    public void save() {
        stopEditing();

        // Build the new list at the SAME size, enforcing the TM-ness invariant per
        // index. By construction each working item already matches slotIsTM[i];
        // the guard below is belt-and-suspenders (and never puts null in the list).
        List<Item> newList = new ArrayList<Item>(working.size());
        for (int i = 0; i < working.size(); i++) {
            Item chosen = working.get(i);
            Item fallback = original.get(i);
            if (chosen == null) {
                // Never null a slot — keep the original item.
                newList.add(fallback);
                continue;
            }
            if (chosen.isTM() != slotIsTM[i]) {
                // Invariant would be violated — revert this slot and note it.
                newList.add(fallback);
                editLog.add("Slot " + i + ": kept " + nameOf(fallback)
                        + " (rejected TM-ness change)");
                continue;
            }
            newList.add(chosen);
        }

        boolean ok = true;
        try {
            romHandler.setFieldItems(newList);
        } catch (Exception e) {
            e.printStackTrace();
            ok = false;
            JOptionPane.showMessageDialog(this,
                    "Failed to save field items:\n" + e.getMessage(),
                    "Save Failed",
                    JOptionPane.ERROR_MESSAGE);
        }
        if (!ok) {
            return;
        }

        if (!editLog.isEmpty()) {
            ManualEditRegistry.getInstance().addEntries("Field Items", new ArrayList<String>(editLog));
            editLog.clear();
        }

        if (!EditorUtils.suppressSaveDialogs) {
            JOptionPane.showMessageDialog(this,
                    "Field items updated!\n\nChanges will be saved when you save/randomize the ROM.",
                    "Save Complete",
                    JOptionPane.INFORMATION_MESSAGE);
        }
    }

    /**
     * Nothing to undo on close: the panel only ever mutates its private working
     * copy, and the handler is touched solely in {@link #save()}. Provided for
     * symmetry with the other editor panels (the frames call it unconditionally).
     */
    public void onWindowClosing() {
        stopEditing();
    }

    private void stopEditing() {
        if (table != null && table.isEditing()) {
            table.getCellEditor().stopCellEditing();
        }
    }

    private static String nameOf(Item it) {
        return it == null ? "(none)" : it.getName();
    }

    private Item itemForOption(int row, String option) {
        if (option == null) {
            return null;
        }
        return slotIsTM[row] ? tmByOption.get(option) : nonTMByOption.get(option);
    }

    private String optionForSlot(int row) {
        Item it = working.get(row);
        if (it == null) {
            return "";
        }
        String label = slotIsTM[row] ? tmOptionById.get(it.getId()) : nonTMOptionById.get(it.getId());
        return label != null ? label : optionLabel(it);
    }

    // ------------------------------------------------------------------
    // Models / components
    // ------------------------------------------------------------------

    private final class SlotTableModel extends AbstractTableModel {
        private final String[] columns = {"#", "TM?", "Item"};

        @Override
        public int getRowCount() {
            return working.size();
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int column) {
            return columns[column];
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            if (columnIndex == 0) {
                return Integer.class;
            }
            return String.class;
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            // Only the Item column is editable; # and TM? are read-only.
            return columnIndex == 2;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            if (columnIndex == 0) {
                return rowIndex;
            }
            if (columnIndex == 1) {
                return slotIsTM[rowIndex] ? "Yes" : "No";
            }
            return optionForSlot(rowIndex);
        }

        @Override
        public void setValueAt(Object aValue, int rowIndex, int columnIndex) {
            if (columnIndex != 2) {
                return;
            }
            Item picked = itemForOption(rowIndex, aValue == null ? null : aValue.toString());
            // Tolerate a null/unknown pick by leaving the existing item untouched,
            // rather than nulling a slot the game expects to be filled.
            if (picked == null) {
                return;
            }
            // Defensive: never accept a pick that would flip this slot's TM-ness.
            if (picked.isTM() != slotIsTM[rowIndex]) {
                return;
            }
            Item old = working.get(rowIndex);
            if (!Objects.equals(old, picked)) {
                working.set(rowIndex, picked);
                editLog.add("Slot " + rowIndex + ": " + nameOf(old) + " -> " + nameOf(picked));
                fireTableRowsUpdated(rowIndex, rowIndex);
            }
        }
    }

    /**
     * Per-row cell editor that delegates to the TM combo for TM slots and the
     * regular combo for non-TM slots, so a row can only resolve to an item that
     * keeps that slot's TM-ness.
     */
    private final class ItemCellEditor extends AbstractCellEditor implements javax.swing.table.TableCellEditor {
        private final DefaultCellEditor tmEditor;
        private final DefaultCellEditor nonTMEditor;
        private DefaultCellEditor active;

        ItemCellEditor(DefaultCellEditor tmEditor, DefaultCellEditor nonTMEditor) {
            this.tmEditor = tmEditor;
            this.nonTMEditor = nonTMEditor;
            this.active = nonTMEditor;
        }

        @Override
        public Object getCellEditorValue() {
            return active.getCellEditorValue();
        }

        @Override
        public boolean stopCellEditing() {
            return active.stopCellEditing();
        }

        @Override
        public void cancelCellEditing() {
            active.cancelCellEditing();
        }

        @Override
        public Component getTableCellEditorComponent(JTable table, Object value, boolean isSelected, int row,
                int column) {
            active = slotIsTM[row] ? tmEditor : nonTMEditor;
            return active.getTableCellEditorComponent(table, value, isSelected, row, column);
        }
    }
}
