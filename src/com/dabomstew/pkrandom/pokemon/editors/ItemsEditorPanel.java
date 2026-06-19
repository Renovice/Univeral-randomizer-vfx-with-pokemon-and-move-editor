package com.dabomstew.pkrandom.pokemon.editors;

import com.dabomstew.pkrandom.log.ManualEditRegistry;
import com.dabomstew.pkromio.gamedata.Item;
import com.dabomstew.pkromio.romhandlers.RomHandler;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableColumnModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Item editor: one scrollable table with a row per {@link Item} returned by
 * {@code romHandler.getItems()}. Generation-agnostic — driven purely through the
 * {@link RomHandler} interface, so the single panel is reused by every generation.
 *
 * <p><b>What is actually writable.</b> An {@link Item}'s {@code id} and
 * {@code name} are <em>final</em>, and there is <b>no item write-back method on
 * {@link RomHandler}</b> (no {@code setItems}/item-name setter exists — the only
 * {@code setItems} in the codebase belongs to {@code Shop} and is unrelated). So
 * names/IDs are fixed by the ROM and are shown <b>read-only</b>; exposing them as
 * editable would be a silent no-op. The only meaningful mutable fields are the
 * flags {@code allowed} and {@code bad}, which the randomizer's item pools read:
 * {@code getAllowedItems()} returns items with {@code allowed == true}, and
 * {@code getNonBadItems()} additionally excludes {@code bad == true}. Toggling
 * them therefore changes which items the randomizer may place (shops, field items,
 * pickup) on the <em>next Randomize</em>. {@code tm} is also mutable on the model
 * but is kept <b>read-only</b> here on purpose: flipping an item's TM-ness would
 * break the field-item TM invariant ({@code setFieldItems} requires each slot keep
 * its TM-ness), so it is displayed only.</p>
 *
 * <p><b>These are in-memory metadata, not ROM bytes.</b> The flags live on the
 * live {@link Item} objects (which {@code getItems()} returns by reference) and are
 * reset whenever the ROM is reloaded. {@link #save()} does <b>not</b> call any
 * {@code setItems} (none exists) — it only copies the working flags onto the live
 * {@link Item} objects and logs the changes.</p>
 *
 * <p>Persistence / apply-on-save model (same spirit as the sibling editors): the
 * panel snapshots each item's original {@code (allowed, bad, tm)} at construction
 * and edits only private working arrays. The live {@link Item} objects are
 * untouched until {@link #save()} applies the working flags. Closing without
 * saving leaks nothing — the live objects were never mutated, so there is nothing
 * to revert.</p>
 */
public class ItemsEditorPanel extends JPanel {

    private static final Font FONT_LABEL = new Font("Segoe UI", Font.PLAIN, 12);

    // Column layout: 0 = ID, 1 = Name, 2 = TM?, 3 = Allowed, 4 = Bad.
    private static final int COL_ID = 0;
    private static final int COL_NAME = 1;
    private static final int COL_TM = 2;
    private static final int COL_ALLOWED = 3;
    private static final int COL_BAD = 4;

    private final RomHandler romHandler;

    // The live items being described, in display order (null entries skipped).
    private final List<Item> items;

    // Pristine snapshot of each row's flags, captured at construction. Used to
    // detect/log changes on save. Parallel to items (same indices).
    private final boolean[] origAllowed;
    private final boolean[] origBad;
    private final boolean[] origTM;

    // Working copies of the editable flags. Edited by the table model; only pushed
    // onto the live Item objects in save(). Parallel to items (same indices).
    private final boolean[] workAllowed;
    private final boolean[] workBad;

    private JTable table;
    private ItemsTableModel model;
    private Runnable saveAction;

    private final EditorUtils.FindState findState = new EditorUtils.FindState();
    private EditorUtils.FindOptions lastFindOptions;

    private final java.util.Set<String> editLog = new LinkedHashSet<String>();

    public ItemsEditorPanel(RomHandler romHandler) {
        this.romHandler = romHandler;

        List<Item> fetched;
        try {
            fetched = romHandler.getItems();
        } catch (Exception e) {
            fetched = new ArrayList<Item>();
        }
        if (fetched == null) {
            fetched = new ArrayList<Item>();
        }

        // Build the row list, skipping null entries (item id 0 / gaps are commonly
        // null in getItems()). We hold references to the LIVE Item objects so save()
        // can apply flags straight onto them.
        this.items = new ArrayList<Item>();
        for (Item it : fetched) {
            if (it != null) {
                items.add(it);
            }
        }

        int n = items.size();
        this.origAllowed = new boolean[n];
        this.origBad = new boolean[n];
        this.origTM = new boolean[n];
        this.workAllowed = new boolean[n];
        this.workBad = new boolean[n];
        for (int i = 0; i < n; i++) {
            Item it = items.get(i);
            origAllowed[i] = it.isAllowed();
            origBad[i] = it.isBad();
            origTM[i] = it.isTM();
            // Working copies start equal to the live values.
            workAllowed[i] = origAllowed[i];
            workBad[i] = origBad[i];
        }

        initUI();
    }

    public void setSaveAction(Runnable saveAction) {
        this.saveAction = saveAction;
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

        JButton findButton = EditorUtils.createStyledButton("Find", new Color(33, 150, 243));
        findButton.addActionListener(e -> doFind());
        toolbar.add(findButton);
        toolbar.add(Box.createHorizontalStrut(10));

        JLabel infoLabel = new JLabel(String.format(
                "Items - %d entries (Allowed/Bad editable; ID, Name and TM? are fixed and shown read-only)",
                items.size()));
        infoLabel.setFont(FONT_LABEL);
        infoLabel.setForeground(EditorTheme.mutedText());
        toolbar.add(infoLabel);

        return toolbar;
    }

    private JPanel buildDetail() {
        JPanel detail = new JPanel(new BorderLayout(0, 8));
        detail.setBackground(EditorTheme.surface());
        detail.setBorder(new EmptyBorder(12, 12, 12, 12));

        // Top muted note explaining exactly what toggling does (and does NOT do).
        JLabel note = new JLabel("<html>Item names/IDs are fixed by the ROM. Toggling <b>Allowed</b>/<b>Bad</b> "
                + "changes which items the randomizer may use (shops, field items, pickup); applied on the next "
                + "Randomize, not written to the ROM file.</html>");
        note.setFont(FONT_LABEL);
        note.setForeground(EditorTheme.mutedText());
        note.setBorder(new EmptyBorder(0, 0, 8, 0));
        detail.add(note, BorderLayout.NORTH);

        model = new ItemsTableModel();
        table = new JTable(model);
        TableLayoutDefaults.applyBaseTableSettings(table);
        // Left-align the Name column (1); ID is centered. The Boolean columns keep
        // JTable's default checkbox renderer/editor (installStripedRenderers does NOT
        // register a Boolean renderer), which is exactly the editable-checkbox look.
        TableLayoutDefaults.installStripedRenderers(table, false, COL_NAME);
        table.setRowHeight(26);
        table.getTableHeader().setReorderingAllowed(false);
        table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        configureColumns();

        JScrollPane tableScroll = new JScrollPane(table);
        tableScroll.setBorder(BorderFactory.createLineBorder(EditorTheme.border()));
        tableScroll.getViewport().setBackground(EditorTheme.surface());
        tableScroll.getVerticalScrollBar().setUnitIncrement(16);
        detail.add(tableScroll, BorderLayout.CENTER);

        JLabel hint = new JLabel("<html><b>Allowed</b> = the item may appear in most places (shops, field items, "
                + "pickup). <b>Bad</b> = excluded from the \"non-bad\" pool some options use. Edits apply to a "
                + "working copy; click Save (or File -> Save All) to apply them to the randomizer.</html>");
        hint.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        hint.setForeground(EditorTheme.mutedText());
        detail.add(hint, BorderLayout.SOUTH);

        return detail;
    }

    private void configureColumns() {
        TableColumnModel cm = table.getColumnModel();

        cm.getColumn(COL_ID).setPreferredWidth(60);
        cm.getColumn(COL_ID).setMaxWidth(90);

        cm.getColumn(COL_NAME).setPreferredWidth(280);

        cm.getColumn(COL_TM).setPreferredWidth(60);
        cm.getColumn(COL_TM).setMaxWidth(90);

        cm.getColumn(COL_ALLOWED).setPreferredWidth(90);
        cm.getColumn(COL_ALLOWED).setMaxWidth(120);

        cm.getColumn(COL_BAD).setPreferredWidth(90);
        cm.getColumn(COL_BAD).setMaxWidth(120);
    }

    // ------------------------------------------------------------------
    // Find
    // ------------------------------------------------------------------

    private void doFind() {
        EditorUtils.FindOptions options = EditorUtils.showFindDialog(this, lastFindOptions);
        if (options == null) {
            return;
        }
        lastFindOptions = options;
        // Single (non-frozen) table: pass it as the main table, no frozen columns.
        EditorUtils.performFind(this, null, table, model, 0, findState, options);
    }

    // ------------------------------------------------------------------
    // Save / persistence
    // ------------------------------------------------------------------

    public void save() {
        stopEditing();

        // Apply the working flags onto the LIVE Item objects and log any change.
        // NOTE: there is deliberately no romHandler.setItems(...) call here — no such
        // method exists. Item flags are in-memory randomizer metadata: getAllowedItems()
        // / getNonBadItems() read them off the live Item objects we mutate below, so this
        // is the entire persistence step. (TM-ness is intentionally not written: it is
        // shown read-only to preserve the field-item TM invariant.)
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            if (it == null) {
                continue;
            }
            if (workAllowed[i] != origAllowed[i]) {
                it.setAllowed(workAllowed[i]);
                editLog.add(it.getName() + ": Allowed " + origAllowed[i] + "->" + workAllowed[i]);
            }
            if (workBad[i] != origBad[i]) {
                it.setBad(workBad[i]);
                editLog.add(it.getName() + ": Bad " + origBad[i] + "->" + workBad[i]);
            }
        }

        // Refresh the snapshot so a subsequent save in the same session only logs
        // further changes (and the working copies remain the baseline).
        for (int i = 0; i < items.size(); i++) {
            origAllowed[i] = workAllowed[i];
            origBad[i] = workBad[i];
        }

        if (!editLog.isEmpty()) {
            ManualEditRegistry.getInstance().addEntries("Item Flags", new ArrayList<String>(editLog));
            editLog.clear();
        }

        if (!EditorUtils.suppressSaveDialogs) {
            JOptionPane.showMessageDialog(this,
                    "Item flags updated!\n\nAllowed/Bad changes apply to the randomizer on the next Randomize.\n"
                            + "(They are in-memory metadata and are not written into the ROM file.)",
                    "Save Complete",
                    JOptionPane.INFORMATION_MESSAGE);
        }
    }

    /**
     * Nothing to undo on close: the panel only ever mutates its private working
     * arrays, and the live {@link Item} objects are touched solely in {@link #save()}.
     * Provided for symmetry with the other editor panels (the frames call it
     * unconditionally).
     */
    public void onWindowClosing() {
        stopEditing();
    }

    private void stopEditing() {
        if (table != null && table.isEditing()) {
            table.getCellEditor().stopCellEditing();
        }
    }

    // ------------------------------------------------------------------
    // Model
    // ------------------------------------------------------------------

    private final class ItemsTableModel extends AbstractTableModel {
        private final String[] columns = {"ID", "Name", "TM?", "Allowed", "Bad"};

        @Override
        public int getRowCount() {
            return items.size();
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
            if (columnIndex == COL_ID) {
                return Integer.class;
            }
            if (columnIndex == COL_ALLOWED || columnIndex == COL_BAD) {
                return Boolean.class;
            }
            return String.class;
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            // Only Allowed and Bad are editable; ID, Name and TM? are read-only.
            return columnIndex == COL_ALLOWED || columnIndex == COL_BAD;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            Item it = items.get(rowIndex);
            switch (columnIndex) {
                case COL_ID:
                    return it.getId();
                case COL_NAME:
                    return it.getName();
                case COL_TM:
                    return origTM[rowIndex] ? "Yes" : "No";
                case COL_ALLOWED:
                    return Boolean.valueOf(workAllowed[rowIndex]);
                case COL_BAD:
                    return Boolean.valueOf(workBad[rowIndex]);
                default:
                    return null;
            }
        }

        @Override
        public void setValueAt(Object aValue, int rowIndex, int columnIndex) {
            boolean newVal = Boolean.TRUE.equals(aValue);
            if (columnIndex == COL_ALLOWED) {
                if (workAllowed[rowIndex] != newVal) {
                    workAllowed[rowIndex] = newVal;
                    fireTableCellUpdated(rowIndex, columnIndex);
                }
            } else if (columnIndex == COL_BAD) {
                if (workBad[rowIndex] != newVal) {
                    workBad[rowIndex] = newVal;
                    fireTableCellUpdated(rowIndex, columnIndex);
                }
            }
        }
    }
}
