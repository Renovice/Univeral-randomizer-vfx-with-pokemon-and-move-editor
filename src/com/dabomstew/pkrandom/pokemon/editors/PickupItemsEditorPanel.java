package com.dabomstew.pkrandom.pokemon.editors;

import com.dabomstew.pkrandom.log.ManualEditRegistry;
import com.dabomstew.pkromio.gamedata.Item;
import com.dabomstew.pkromio.gamedata.PickupItem;
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
 * Pickup-Items editor: one scrollable table with a row per {@link PickupItem}
 * (the entries of the Pickup ability item tables). Generation-agnostic —
 * everything is driven through the {@link RomHandler} interface
 * ({@code getPickupItems} / {@code setPickupItems}), so the single panel is
 * reused by every generation that exposes pickup data (Gen3/4+).
 *
 * <p><b>What is actually writable.</b> Every {@code setPickupItems}
 * implementation in this fork (Gen3/4/5/6/7) writes back <em>only the item id</em>
 * of each row ({@code getItem().getId()}); the per-bracket probabilities are
 * never persisted. In Gen3-6 the probabilities don't even live in the ROM — they
 * are synthesised as fixed constants from the game's hard-coded pickup algorithm
 * inside {@code getPickupItems}; in Gen7 they are read from the file but the
 * setter still ignores them. So this panel makes the <b>Item</b> column editable
 * (changing it builds {@code new PickupItem(chosenItem)} and copies the original
 * 10 probabilities into it, exactly like {@code ItemRandomizer.randomizePickupItems})
 * and renders the <b>probability</b> columns <b>read-only</b> — exposing an
 * editable probability cell would silently no-op, which we deliberately avoid.</p>
 *
 * <p>The 10 probability columns map to the game's fixed level brackets
 * (levelRange {@code i} = levels {@code i*10+1 .. (i+1)*10}), i.e. Lv 1-10 ..
 * Lv 91-100, matching {@code RandomizationLogger.logPickupItems()}. A footer row
 * shows each bracket column's sum as a guide (vanilla brackets normally sum to
 * 100) — informational only, never enforced.</p>
 *
 * <p>Persistence model (same as the sibling editors): the panel fetches the
 * pickup list once and edits only a private working copy. The live handler data
 * is untouched until {@link #save()} pushes the working copy via
 * {@code setPickupItems(...)}. Closing without saving leaks nothing — there is
 * nothing to revert because the handler was never mutated.</p>
 */
public class PickupItemsEditorPanel extends JPanel {

    private static final Font FONT_LABEL = new Font("Segoe UI", Font.PLAIN, 12);
    private static final Font FONT_VALUE = new Font("Segoe UI", Font.BOLD, 12);

    // Column layout: 0 = #, 1 = Item, 2..11 = the 10 probability brackets.
    private static final int COL_INDEX = 0;
    private static final int COL_ITEM = 1;
    private static final int COL_FIRST_PROB = 2;
    private static final int PROB_COUNT = PickupItem.PROBABILITY_SLOTS; // 10

    private final RomHandler romHandler;

    // The rows being edited. working.get(i) is the current pick for row i; it is
    // initialised from a copy of getPickupItems() (deep-copied via the PickupItem
    // copy constructor so probabilities are independent of the handler's data).
    private final List<PickupItem> working;
    // Pristine snapshot of getPickupItems(), used as the safe fallback for a row
    // that ends up with no valid selection. Also deep copies.
    private final List<PickupItem> original;

    // Combo option strings ("<id>: <name>") -> Item, plus a reverse id->option map
    // so a row's current item resolves back to its display option.
    private final String[] itemOptions;
    private final Map<String, Item> itemByOption = new LinkedHashMap<String, Item>();
    private final Map<Integer, String> optionById = new LinkedHashMap<Integer, String>();

    private JTable table;
    private PickupTableModel model;
    private Runnable saveAction;

    private final java.util.Set<String> editLog = new LinkedHashSet<String>();

    public PickupItemsEditorPanel(RomHandler romHandler) {
        this.romHandler = romHandler;

        List<PickupItem> fetched;
        try {
            fetched = romHandler.getPickupItems();
        } catch (Exception e) {
            fetched = new ArrayList<PickupItem>();
        }
        if (fetched == null) {
            fetched = new ArrayList<PickupItem>();
        }

        // Deep-copy each PickupItem so our working/original probability arrays are
        // independent of (and don't alias) the handler's freshly-built objects.
        this.original = deepCopy(fetched);
        this.working = deepCopy(fetched);

        // Item pool mirrors ItemRandomizer.randomizePickupItems(): the allowed-items
        // set. Sorted by id and labelled "<id>: <name>". Every item currently sitting
        // in a row is force-included so a vanilla pick is never silently dropped.
        List<Item> pool = buildItemPool();
        this.itemOptions = buildOptions(pool, itemByOption, optionById);

        initUI();
    }

    public void setSaveAction(Runnable saveAction) {
        this.saveAction = saveAction;
    }

    private static List<PickupItem> deepCopy(List<PickupItem> src) {
        List<PickupItem> copy = new ArrayList<PickupItem>(src.size());
        for (PickupItem pi : src) {
            copy.add(pi == null ? null : new PickupItem(pi));
        }
        return copy;
    }

    // ------------------------------------------------------------------
    // Item pool
    // ------------------------------------------------------------------

    private List<Item> buildItemPool() {
        List<Item> pool = new ArrayList<Item>();
        try {
            for (Item it : romHandler.getAllowedItems()) {
                if (it != null) {
                    pool.add(it);
                }
            }
        } catch (Exception ignored) {
        }
        // Guarantee a row's current item is always offered, even if it isn't in the
        // allowed set (so we never silently drop a vanilla pickup item).
        for (PickupItem pi : original) {
            if (pi == null) {
                continue;
            }
            Item it = pi.getItem();
            if (it != null && !pool.contains(it)) {
                pool.add(it);
            }
        }
        sortById(pool);
        return pool;
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

        JLabel infoLabel = new JLabel(String.format(
                "Pickup Items - %d entries (Item editable; probabilities are game-fixed and shown read-only)",
                working.size()));
        infoLabel.setFont(FONT_LABEL);
        infoLabel.setForeground(EditorTheme.mutedText());
        toolbar.add(infoLabel);

        return toolbar;
    }

    private JPanel buildDetail() {
        JPanel detail = new JPanel(new BorderLayout(0, 8));
        detail.setBackground(EditorTheme.surface());
        detail.setBorder(new EmptyBorder(12, 12, 12, 12));

        model = new PickupTableModel();
        table = new JTable(model);
        TableLayoutDefaults.applyBaseTableSettings(table);
        // Left-align the Item column (1); numeric columns stay centered/right via default.
        TableLayoutDefaults.installStripedRenderers(table, false, COL_ITEM);
        table.setRowHeight(26);
        table.getTableHeader().setReorderingAllowed(false);
        table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        configureColumns();

        JScrollPane tableScroll = new JScrollPane(table);
        tableScroll.setBorder(BorderFactory.createLineBorder(EditorTheme.border()));
        tableScroll.getViewport().setBackground(EditorTheme.surface());
        tableScroll.getVerticalScrollBar().setUnitIncrement(16);
        detail.add(tableScroll, BorderLayout.CENTER);

        JLabel hint = new JLabel("<html>Each row is a Pickup-table slot. Only the <b>Item</b> is editable "
                + "(the probabilities are baked into the game's pickup algorithm and are not written back, so "
                + "they are read-only). Columns are level brackets; the bottom <b>Sum</b> row is a guide only "
                + "(brackets normally total 100). Edits apply to a working copy; click Save (or File -> Save All) "
                + "to keep them.</html>");
        hint.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        hint.setForeground(EditorTheme.mutedText());
        detail.add(hint, BorderLayout.SOUTH);

        return detail;
    }

    private void configureColumns() {
        TableColumnModel cm = table.getColumnModel();

        cm.getColumn(COL_INDEX).setPreferredWidth(50);
        cm.getColumn(COL_INDEX).setMaxWidth(70);

        TableColumn itemCol = cm.getColumn(COL_ITEM);
        itemCol.setPreferredWidth(280);
        final JComboBox<String> itemCombo = new JComboBox<String>(itemOptions);
        itemCombo.setFont(FONT_VALUE);
        EditorUtils.installSearchableComboBox(itemCombo);
        itemCol.setCellEditor(new DefaultCellEditor(itemCombo));

        // The 10 probability columns: narrow, read-only display (rendered by the
        // shared striped Integer renderer installed in buildDetail()).
        for (int p = 0; p < PROB_COUNT; p++) {
            TableColumn c = cm.getColumn(COL_FIRST_PROB + p);
            c.setPreferredWidth(70);
            c.setMaxWidth(110);
        }
    }

    // Level bracket label for probability slot p: levels p*10+1 .. (p+1)*10.
    // Matches RandomizationLogger.logPickupItems().
    private static String bracketLabel(int p) {
        int start = (p * 10) + 1;
        int end = (p + 1) * 10;
        return "Lv " + start + "-" + end;
    }

    // ------------------------------------------------------------------
    // Save / persistence
    // ------------------------------------------------------------------

    public void save() {
        stopEditing();

        // Build the new list at the SAME size and order. For an unchanged row reuse a
        // fresh copy of the working item; for an item-changed row the working entry is
        // already a new PickupItem(chosenItem) carrying the copied probabilities. The
        // null-guard never lets a null row reach setPickupItems (PickupItem forbids a
        // null item, so a null row would otherwise be impossible to reconstruct).
        List<PickupItem> newList = new ArrayList<PickupItem>(working.size());
        for (int i = 0; i < working.size(); i++) {
            PickupItem chosen = working.get(i);
            PickupItem fallback = original.get(i);
            if (chosen == null || chosen.getItem() == null) {
                // Never null a slot — keep the original entry.
                newList.add(fallback == null ? null : new PickupItem(fallback));
                continue;
            }
            newList.add(new PickupItem(chosen));
        }

        boolean ok = true;
        try {
            romHandler.setPickupItems(newList);
        } catch (Exception e) {
            e.printStackTrace();
            ok = false;
            JOptionPane.showMessageDialog(this,
                    "Failed to save pickup items:\n" + e.getMessage(),
                    "Save Failed",
                    JOptionPane.ERROR_MESSAGE);
        }
        if (!ok) {
            return;
        }

        if (!editLog.isEmpty()) {
            ManualEditRegistry.getInstance().addEntries("Pickup Items", new ArrayList<String>(editLog));
            editLog.clear();
        }

        if (!EditorUtils.suppressSaveDialogs) {
            JOptionPane.showMessageDialog(this,
                    "Pickup items updated!\n\nChanges will be saved when you save/randomize the ROM.",
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

    private static String nameOf(PickupItem pi) {
        if (pi == null || pi.getItem() == null) {
            return "(none)";
        }
        return pi.getItem().getName();
    }

    private String optionForRow(int row) {
        PickupItem pi = working.get(row);
        if (pi == null || pi.getItem() == null) {
            return "";
        }
        Item it = pi.getItem();
        String label = optionById.get(it.getId());
        return label != null ? label : optionLabel(it);
    }

    /** Sum of all rows' probabilities in bracket {@code p}. */
    private int columnSum(int p) {
        int sum = 0;
        for (PickupItem pi : working) {
            if (pi != null) {
                sum += pi.getProbabilities()[p];
            }
        }
        return sum;
    }

    // ------------------------------------------------------------------
    // Model
    // ------------------------------------------------------------------

    private final class PickupTableModel extends AbstractTableModel {

        @Override
        public int getRowCount() {
            // One extra row at the bottom for the per-bracket Sum guide.
            return working.size() + 1;
        }

        @Override
        public int getColumnCount() {
            return COL_FIRST_PROB + PROB_COUNT; // # + Item + 10 brackets
        }

        @Override
        public String getColumnName(int column) {
            if (column == COL_INDEX) {
                return "#";
            }
            if (column == COL_ITEM) {
                return "Item";
            }
            return bracketLabel(column - COL_FIRST_PROB);
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            if (columnIndex == COL_INDEX) {
                return Integer.class;
            }
            if (columnIndex == COL_ITEM) {
                return String.class;
            }
            return Integer.class;
        }

        private boolean isSumRow(int rowIndex) {
            return rowIndex == working.size();
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            // Only the Item column on a real (non-sum) row is editable. Probabilities
            // are read-only because no handler writes them back.
            return columnIndex == COL_ITEM && !isSumRow(rowIndex);
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            if (isSumRow(rowIndex)) {
                if (columnIndex == COL_INDEX) {
                    return null;
                }
                if (columnIndex == COL_ITEM) {
                    return "Sum";
                }
                return columnSum(columnIndex - COL_FIRST_PROB);
            }
            if (columnIndex == COL_INDEX) {
                return rowIndex;
            }
            if (columnIndex == COL_ITEM) {
                return optionForRow(rowIndex);
            }
            PickupItem pi = working.get(rowIndex);
            if (pi == null) {
                return 0;
            }
            return pi.getProbabilities()[columnIndex - COL_FIRST_PROB];
        }

        @Override
        public void setValueAt(Object aValue, int rowIndex, int columnIndex) {
            if (columnIndex != COL_ITEM || isSumRow(rowIndex)) {
                return;
            }
            Item picked = aValue == null ? null : itemByOption.get(aValue.toString());
            // Tolerate a null/unknown pick by leaving the existing item untouched,
            // rather than nulling a slot the game expects to be filled.
            if (picked == null) {
                return;
            }
            PickupItem oldEntry = working.get(rowIndex);
            Item oldItem = oldEntry == null ? null : oldEntry.getItem();
            if (Objects.equals(oldItem, picked)) {
                return;
            }
            // Build a new PickupItem for the chosen item and carry over the original
            // 10 probabilities (mirrors ItemRandomizer.randomizePickupItems()).
            PickupItem replacement = new PickupItem(picked);
            if (oldEntry != null) {
                int[] src = oldEntry.getProbabilities();
                int[] dst = replacement.getProbabilities();
                System.arraycopy(src, 0, dst, 0, PROB_COUNT);
            }
            working.set(rowIndex, replacement);
            editLog.add("Row " + rowIndex + ": " + nameOf(oldEntry) + " -> " + nameOf(replacement));
            fireTableRowsUpdated(rowIndex, rowIndex);
            // The Sum row is unaffected by item changes (probabilities are preserved),
            // but refresh it cheaply for consistency.
            fireTableRowsUpdated(working.size(), working.size());
        }
    }
}
