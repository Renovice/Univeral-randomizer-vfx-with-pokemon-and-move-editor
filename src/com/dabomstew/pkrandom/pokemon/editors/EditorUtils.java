package com.dabomstew.pkrandom.pokemon.editors;

import com.dabomstew.pkrandom.log.ManualEditRegistry;
import com.dabomstew.pkromio.gamedata.Evolution;
import com.dabomstew.pkromio.gamedata.EvolutionType;
import com.dabomstew.pkromio.gamedata.Move;
import com.dabomstew.pkromio.gamedata.Species;
import com.dabomstew.pkromio.romhandlers.RomHandler;
import javax.swing.*;
import javax.swing.event.ChangeListener;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileFilter;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableColumn;
import javax.swing.table.TableModel;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.ComboBoxModel;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Utility methods for editor panels
 */
public class EditorUtils {

    private static final String SUPPRESS_FROZEN_SYNC_KEY = "EditorUtils.suppressFrozenSync";
    private static final String FULL_ROW_SELECTION_ACTIVE_KEY = "EditorUtils.fullRowSelectionActive";
    private static final String COMBOBOX_SEARCH_INSTALLED_KEY = "EditorUtils.comboSearchInstalled";

    /**
     * When true, individual panel {@code save()} methods skip their own
     * "...saved successfully!" confirmation dialog. The "Save All" path on each
     * editor frame sets this for the duration of the batch so the user sees a
     * single final confirmation instead of one popup per panel. A panel's own
     * "Save" button leaves it false, so single-panel saves still confirm.
     */
    public static boolean suppressSaveDialogs = false;

    /**
     * Rebuilds every species' {@code evolutionsTo} list from the authoritative
     * {@code evolutionsFrom} lists, restoring the invariant that each Evolution
     * instance appears both in its source's from-list and its target's to-list.
     *
     * The evolution editors only mutate {@code evolutionsFrom}; calling this
     * after any change keeps the reverse links (which the randomizer consumes)
     * consistent. Cheap and idempotent — safe to call on every table change.
     *
     * @param allSpecies the full species list (use getSpeciesInclFormes()).
     */
    public static void rebuildEvolutionReverseLinks(List<Species> allSpecies) {
        if (allSpecies == null) {
            return;
        }
        for (Species sp : allSpecies) {
            if (sp != null && sp.getEvolutionsTo() != null) {
                sp.getEvolutionsTo().clear();
            }
        }
        for (Species sp : allSpecies) {
            if (sp == null) {
                continue;
            }
            List<Evolution> from = sp.getEvolutionsFrom();
            if (from == null) {
                continue;
            }
            for (Evolution evo : from) {
                if (evo != null && evo.getTo() != null && evo.getTo().getEvolutionsTo() != null) {
                    evo.getTo().getEvolutionsTo().add(evo);
                }
            }
        }
    }

    /**
     * Removes every {@code EvolutionType.NONE} (placeholder/phantom) evolution from
     * each species' {@code evolutionsFrom} list. The evolutions editor creates NONE
     * slots while a row is being edited; this strips them so they never persist or
     * pollute the reverse links. Returns true if anything was removed.
     *
     * @param allSpecies the full species list (use getSpeciesInclFormes()).
     */
    public static boolean stripNoneEvolutions(List<Species> allSpecies) {
        if (allSpecies == null) {
            return false;
        }
        boolean removedAny = false;
        for (Species sp : allSpecies) {
            if (sp == null) {
                continue;
            }
            List<Evolution> from = sp.getEvolutionsFrom();
            if (from == null) {
                continue;
            }
            for (java.util.Iterator<Evolution> it = from.iterator(); it.hasNext();) {
                Evolution evo = it.next();
                if (evo == null || evo.getType() == EvolutionType.NONE) {
                    it.remove();
                    removedAny = true;
                }
            }
        }
        return removedAny;
    }

    /**
     * Returns every species in the same evolutionary family as {@code start} —
     * the connected component reached by following {@code evolutionsFrom} (down)
     * and {@code evolutionsTo} (up) transitively. Includes {@code start} itself
     * and correctly covers branching families (Eevee, Wurmple, Tyrogue, …).
     */
    public static List<Species> evolutionFamily(Species start) {
        List<Species> family = new ArrayList<Species>();
        if (start == null) {
            return family;
        }
        LinkedHashSet<Species> seen = new LinkedHashSet<Species>();
        seen.add(start);
        List<Species> work = new ArrayList<Species>();
        work.add(start);
        for (int i = 0; i < work.size(); i++) {
            Species cur = work.get(i);
            if (cur.getEvolutionsFrom() != null) {
                for (Evolution e : cur.getEvolutionsFrom()) {
                    if (e != null && e.getTo() != null && seen.add(e.getTo())) {
                        work.add(e.getTo());
                    }
                }
            }
            if (cur.getEvolutionsTo() != null) {
                for (Evolution e : cur.getEvolutionsTo()) {
                    if (e != null && e.getFrom() != null && seen.add(e.getFrom())) {
                        work.add(e.getFrom());
                    }
                }
            }
        }
        family.addAll(seen);
        return family;
    }

    /**
     * Returns the species name combined with its forme suffix, if any.
     */
    public static String speciesNameWithSuffix(Species species) {
        if (species == null) {
            return "";
        }
        String baseName = species.getName() != null ? species.getName() : "";
        String suffix = species.getFormeSuffix();
        if (suffix != null && !suffix.isEmpty()) {
            return baseName + suffix;
        }
        return baseName;
    }

    private static int formeDepth(Species species) {
        int depth = 0;
        Species current = species;
        while (current != null && !current.isBaseForme()) {
            depth++;
            current = current.getBaseForme();
        }
        return depth;
    }

    /**
     * Returns a display-ready name for species, indenting and prefixing formes.
     */
    public static String formatSpeciesDisplayName(Species species) {
        if (species == null) {
            return "";
        }
        String nameWithSuffix = speciesNameWithSuffix(species);
        int depth = formeDepth(species);
        if (depth <= 0) {
            return nameWithSuffix;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i < depth; i++) {
            sb.append("  ");
        }
        sb.append("-> ").append(nameWithSuffix);
        return sb.toString();
    }

    /**
     * Formats a display name suitable for combo boxes that include the species id.
     */
    public static String formatSpeciesDisplayNameWithId(Species species) {
        if (species == null) {
            return "0: (None)";
        }
        return species.getNumber() + ": " + formatSpeciesDisplayName(species);
    }

    /**
     * Parses the leading species id from a string formatted as "<id>: <name>".
     * Returns -1 if the id cannot be read.
     */
    public static int parseLeadingSpeciesId(String value) {
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
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    /**
     * Export table data to CSV file
     */
    public static void exportTableToCSV(Component parent, TableModel tableModel, String sheetName) {
        JFileChooser fc = new JFileChooser(System.getProperty("user.dir"));
        fc.setDialogTitle("Export " + sheetName + " to CSV");
        fc.setAcceptAllFileFilterUsed(false);
        fc.setFileSelectionMode(JFileChooser.FILES_ONLY);

        fc.addChoosableFileFilter(new FileFilter() {
            @Override
            public boolean accept(File f) {
                return f.isDirectory() || f.getName().toLowerCase().endsWith(".csv");
            }

            @Override
            public String getDescription() {
                return "CSV file (*.csv)";
            }
        });

        int returnVal = fc.showSaveDialog(parent);

        if (returnVal == JFileChooser.APPROVE_OPTION) {
            File selected = fc.getSelectedFile();
            String path = selected.getAbsolutePath();
            if (!path.toLowerCase().endsWith(".csv")) {
                path = path + ".csv";
            }

            try (BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(new FileOutputStream(path), StandardCharsets.UTF_8))) {
                // Write header row
                for (int col = 0; col < tableModel.getColumnCount(); col++) {
                    String colName = tableModel.getColumnName(col);
                    // Remove HTML tags from column names
                    colName = colName.replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ").trim();
                    writer.write(escapeCSV(colName));
                    if (col < tableModel.getColumnCount() - 1) {
                        writer.write(",");
                    }
                }
                writer.write("\n");

                // Write data rows
                for (int row = 0; row < tableModel.getRowCount(); row++) {
                    for (int col = 0; col < tableModel.getColumnCount(); col++) {
                        Object value = tableModel.getValueAt(row, col);
                        if (value != null) {
                            writer.write(escapeCSV(value.toString()));
                        }
                        if (col < tableModel.getColumnCount() - 1) {
                            writer.write(",");
                        }
                    }
                    writer.write("\n");
                }

                JOptionPane.showMessageDialog(parent,
                        "Successfully exported to:\n" + path,
                        "Export Complete",
                        JOptionPane.INFORMATION_MESSAGE);
            } catch (IOException e) {
                e.printStackTrace();
                JOptionPane.showMessageDialog(parent,
                        "Error exporting CSV: " + e.getMessage(),
                        "Export Error",
                        JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    /**
     * Escape CSV special characters
     */
    private static String escapeCSV(String value) {
        // Quote on the standard triggers (comma, quote, newline), and also on a
        // bare carriage return (BufferedReader.readLine treats a lone '\r' as a
        // line break on import, so an unquoted CR-bearing cell would split into
        // two records) and on leading/trailing whitespace (so spaces survive
        // arbitrary external CSV tooling intact).
        boolean edgeWhitespace = !value.isEmpty()
                && (Character.isWhitespace(value.charAt(0))
                        || Character.isWhitespace(value.charAt(value.length() - 1)));
        if (value.contains(",") || value.contains("\"") || value.contains("\n")
                || value.contains("\r") || edgeWhitespace) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    /**
     * Create a styled button for editor toolbars
     */
    public static JButton createStyledButton(String text, Color bgColor) {
        JButton button = new JButton(text) {
            @Override
            protected void paintComponent(Graphics g) {
                g.setColor(getBackground());
                g.fillRect(0, 0, getWidth(), getHeight());
                super.paintComponent(g);
            }
        };

        button.setFont(new Font("Segoe UI", Font.BOLD, 12));
        button.setForeground(Color.WHITE);
        button.setBackground(bgColor);
        button.setFocusPainted(false);
        button.setOpaque(false);
        button.setContentAreaFilled(false);
        button.setBorderPainted(true);
        button.setCursor(new Cursor(Cursor.HAND_CURSOR));

        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(bgColor.darker(), 2),
                BorderFactory.createEmptyBorder(8, 16, 8, 16)));

        return button;
    }

    /**
     * Create a styled toggle button for editor toolbars
     */
    public static JToggleButton createStyledToggleButton(String text, Color bgColor) {
        JToggleButton button = new JToggleButton(text) {
            @Override
            protected void paintComponent(Graphics g) {
                if (isSelected()) {
                    g.setColor(bgColor.darker());
                } else {
                    g.setColor(getBackground());
                }
                g.fillRect(0, 0, getWidth(), getHeight());
                super.paintComponent(g);
            }
        };

        button.setFont(new Font("Segoe UI", Font.BOLD, 12));
        button.setForeground(Color.WHITE);
        button.setBackground(bgColor);
        button.setFocusPainted(false);
        button.setOpaque(false);
        button.setContentAreaFilled(false);
        button.setBorderPainted(true);
        button.setCursor(new Cursor(Cursor.HAND_CURSOR));

        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(bgColor.darker(), 2),
                BorderFactory.createEmptyBorder(8, 16, 8, 16)));

        return button;
    }

    /**
     * Prompt the user to select a CSV file and return the parsed data.
     */
    public static CsvData chooseCsvFile(Component parent, String sheetName) {
        JFileChooser fc = new JFileChooser(System.getProperty("user.dir"));
        fc.setDialogTitle("Import " + sheetName + " from CSV");
        fc.setAcceptAllFileFilterUsed(false);
        fc.setFileSelectionMode(JFileChooser.FILES_ONLY);
        fc.addChoosableFileFilter(new FileFilter() {
            @Override
            public boolean accept(File f) {
                return f.isDirectory() || f.getName().toLowerCase().endsWith(".csv");
            }

            @Override
            public String getDescription() {
                return "CSV file (*.csv)";
            }
        });

        int result = fc.showOpenDialog(parent);
        if (result != JFileChooser.APPROVE_OPTION) {
            return null;
        }

        File selected = fc.getSelectedFile();
        List<String[]> rows;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(selected), StandardCharsets.UTF_8))) {
            // Read the whole file and tokenize with a quote-aware parser so that a
            // quoted field containing an embedded newline (escapeCSV quotes such
            // values on export) is kept as a single field instead of being split
            // across physical lines. A line-by-line readLine() loop cannot carry
            // the inQuotes state across line breaks, which corrupts such rows.
            StringBuilder content = new StringBuilder();
            char[] buffer = new char[8192];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                content.append(buffer, 0, read);
            }
            rows = parseCsvContent(content.toString());
            if (!rows.isEmpty() && rows.get(0).length > 0) {
                rows.get(0)[0] = stripBom(rows.get(0)[0]);
            }
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(parent,
                    "Error reading CSV: " + ex.getMessage(),
                    "Import Error",
                    JOptionPane.ERROR_MESSAGE);
            return null;
        }

        if (rows.isEmpty()) {
            JOptionPane.showMessageDialog(parent,
                    "CSV file is empty.",
                    "Import Error",
                    JOptionPane.WARNING_MESSAGE);
            return null;
        }

        return new CsvData(selected, rows);
    }

    /**
     * Apply CSV data to a table model.
     *
     * @return number of rows that were updated
     */
    public static int applyCsvDataToTable(List<String[]> rows, TableModel model, boolean skipNonEditable) {
        return applyCsvDataToTable(rows, model, skipNonEditable, null);
    }

    /**
     * Apply CSV data to a table model with row-identity validation.
     *
     * <p>When the model's first column is an ID/number column, each CSV row is
     * matched to the model row that shares its ID (rather than applied blindly by
     * position). A CSV row whose ID does not match any model row — or that is
     * shorter than the expected column count — is SKIPPED instead of silently
     * corrupting an unrelated species. If a model has no usable ID column, the
     * method falls back to positional application but still rejects short rows.
     * The number of skipped rows is surfaced via a warning dialog (when a parent
     * component is supplied) and is reflected in the returned applied count.
     *
     * @param parent component to anchor the skipped-rows warning dialog (may be null)
     * @return number of rows that were actually applied
     */
    public static int applyCsvDataToTable(List<String[]> rows, TableModel model, boolean skipNonEditable,
            Component parent) {
        if (rows == null || rows.size() <= 1) {
            throw new IllegalArgumentException("CSV file does not contain any data rows.");
        }

        int expectedColumns = model.getColumnCount();
        if (expectedColumns == 0) {
            throw new IllegalArgumentException("Target table has no columns.");
        }

        String[] header = rows.get(0);
        if (header.length != expectedColumns) {
            throw new IllegalArgumentException(
                    "Column count mismatch between CSV (" + header.length + ") and table (" + expectedColumns + ").");
        }

        // Decide whether column 0 is a usable ID column. We treat it as an ID column
        // when its header is named "ID" (case-insensitive); this matches every sheet
        // editor that exports an "ID" first column. As a safety net, we also treat
        // column 0 as an ID column when the model's column-0 class is numeric, so a
        // manually renamed/translated/blank header still gets identity matching
        // instead of silently dropping to the corrupt-a-neighbour positional path.
        // When usable, we build an ID -> model-row index so rows can be matched by
        // identity instead of position.
        boolean idColumn = "ID".equalsIgnoreCase(header[0] == null ? "" : header[0].trim());
        if (!idColumn) {
            Class<?> col0Class = model.getColumnClass(0);
            idColumn = col0Class != null && Number.class.isAssignableFrom(col0Class);
        }
        java.util.Map<String, Integer> idToModelRow = null;
        if (idColumn) {
            idToModelRow = new java.util.HashMap<String, Integer>();
            for (int r = 0; r < model.getRowCount(); r++) {
                String id = normalizeId(model.getValueAt(r, 0));
                if (id != null && !idToModelRow.containsKey(id)) {
                    idToModelRow.put(id, r);
                }
            }
            // If the model's IDs didn't parse usefully, fall back to positional mode.
            if (idToModelRow.isEmpty()) {
                idColumn = false;
                idToModelRow = null;
            }
        }

        int applied = 0;
        int skippedShort = 0;
        int skippedMismatch = 0;
        int skippedCells = 0;

        int csvDataRows = rows.size() - 1;
        for (int csvIndex = 0; csvIndex < csvDataRows; csvIndex++) {
            String[] csvRow = rows.get(csvIndex + 1);

            // Reject rows that are shorter than the table expects (data-loss guard) —
            // these are never padded-and-applied any more.
            if (csvRow.length < expectedColumns) {
                skippedShort++;
                continue;
            }

            int targetRow;
            if (idColumn) {
                String csvId = normalizeId(csvRow.length > 0 ? csvRow[0] : null);
                Integer mapped = csvId == null ? null : idToModelRow.get(csvId);
                if (mapped == null) {
                    // No model row with this ID — skip rather than corrupt a neighbour.
                    skippedMismatch++;
                    continue;
                }
                targetRow = mapped;
            } else {
                // Positional fallback (model has no usable ID column).
                if (csvIndex >= model.getRowCount()) {
                    break;
                }
                targetRow = csvIndex;
            }

            for (int colIndex = 0; colIndex < expectedColumns; colIndex++) {
                if (skipNonEditable && !model.isCellEditable(targetRow, colIndex)) {
                    continue;
                }
                String rawValue = csvRow[colIndex] != null ? csvRow[colIndex] : "";
                Object coerced = coerceValue(rawValue, model.getColumnClass(colIndex));
                if (coerced == UNPARSEABLE_CELL) {
                    // Value could not be parsed to the column's type — leave the
                    // existing cell value rather than letting the model coerce it to 0.
                    skippedCells++;
                    continue;
                }
                model.setValueAt(coerced, targetRow, colIndex);
            }
            applied++;
        }

        int skipped = skippedShort + skippedMismatch;
        if ((skipped > 0 || skippedCells > 0) && parent != null) {
            StringBuilder msg = new StringBuilder();
            if (skipped > 0) {
                msg.append("Skipped ").append(skipped)
                        .append(" CSV row(s) to avoid corrupting unrelated entries:\n");
            } else {
                msg.append("Some CSV cells were not applied:\n");
            }
            if (skippedShort > 0) {
                msg.append("  - ").append(skippedShort)
                        .append(" row(s) had fewer columns than expected (")
                        .append(expectedColumns).append(").\n");
            }
            if (skippedMismatch > 0) {
                msg.append("  - ").append(skippedMismatch)
                        .append(" row(s) had an ID with no matching table row.\n");
            }
            if (skippedCells > 0) {
                msg.append("  - ").append(skippedCells)
                        .append(" cell(s) had a non-numeric value and were left unchanged.\n");
            }
            msg.append("\nApplied ").append(applied).append(" row(s).");
            JOptionPane.showMessageDialog(parent, msg.toString(), "Import Warning",
                    JOptionPane.WARNING_MESSAGE);
        }

        return applied;
    }

    /**
     * Normalizes an ID cell value to a canonical string for matching. Accepts
     * Integer values and "12: Name"-style strings, returning the leading number.
     * Returns null when no usable numeric ID can be extracted.
     */
    private static String normalizeId(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return String.valueOf(((Number) value).longValue());
        }
        String text = value.toString().trim();
        if (text.isEmpty()) {
            return null;
        }
        // Take the leading run of digits (handles plain "12" and "12: Name").
        int i = 0;
        if (text.charAt(0) == '-' || text.charAt(0) == '+') {
            i = 1;
        }
        int start = i;
        while (i < text.length() && Character.isDigit(text.charAt(i))) {
            i++;
        }
        if (i == start) {
            return null;
        }
        try {
            return String.valueOf(Long.parseLong(text.substring(0, i)));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /**
     * Display a shared Find dialog and capture search options.
     */
    public static FindOptions showFindDialog(Component parent, FindOptions previousOptions) {
        JTextField queryField = new JTextField(previousOptions != null ? previousOptions.getQuery() : "");
        JCheckBox matchCase = new JCheckBox("Match case", previousOptions != null && previousOptions.isMatchCase());
        JCheckBox matchEntire = new JCheckBox("Match entire cell",
                previousOptions != null && previousOptions.isMatchEntireCell());

        JPanel panel = new JPanel(new GridLayout(0, 1, 0, 4));
        panel.add(new JLabel("Find what:"));
        panel.add(queryField);
        panel.add(matchCase);
        panel.add(matchEntire);

        int result = JOptionPane.showConfirmDialog(parent, panel, "Find", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION) {
            return null;
        }

        String query = queryField.getText();
        if (query == null || query.trim().isEmpty()) {
            JOptionPane.showMessageDialog(parent,
                    "Enter text to search for.",
                    "Find",
                    JOptionPane.INFORMATION_MESSAGE);
            return null;
        }

        return new FindOptions(query.trim(), matchCase.isSelected(), matchEntire.isSelected());
    }

    /**
     * Execute a find operation on the supplied table model.
     */
    public static boolean performFind(Component parent,
            JTable frozenTable,
            JTable mainTable,
            TableModel model,
            int frozenColumns,
            FindState state,
            FindOptions options) {
        if (options == null) {
            return false;
        }

        String query = options.getQuery();
        if (query == null || query.isEmpty()) {
            JOptionPane.showMessageDialog(parent,
                    "Enter text to search for.",
                    "Find",
                    JOptionPane.INFORMATION_MESSAGE);
            return false;
        }

        int rowCount = model.getRowCount();
        int columnCount = model.getColumnCount();
        if (rowCount == 0 || columnCount == 0) {
            JOptionPane.showMessageDialog(parent,
                    "There is no data to search.",
                    "Find",
                    JOptionPane.INFORMATION_MESSAGE);
            return false;
        }

        int totalCells = rowCount * columnCount;
        int startIndex = 0;
        if (state != null && state.canContinueWith(options)) {
            startIndex = state.getLastIndex(columnCount) + 1;
        }

        String normalizedQuery = options.isMatchCase() ? query : query.toLowerCase(Locale.ROOT);

        for (int offset = 0; offset < totalCells; offset++) {
            int linearIndex = (startIndex + offset) % totalCells;
            int row = linearIndex / columnCount;
            int col = linearIndex % columnCount;

            Object value = model.getValueAt(row, col);
            String text = value == null ? "" : value.toString();
            String compareText = options.isMatchCase() ? text : text.toLowerCase(Locale.ROOT);

            boolean match = options.isMatchEntireCell()
                    ? compareText.equals(normalizedQuery)
                    : compareText.contains(normalizedQuery);

            if (match) {
                if (state != null) {
                    state.update(options, row, col);
                }
                selectCell(frozenTable, mainTable, frozenColumns, row, col);
                return true;
            }
        }

        if (state != null) {
            state.update(options, -1, -1);
        }

        Toolkit.getDefaultToolkit().beep();
        JOptionPane.showMessageDialog(parent,
                "No matches found for \"" + query + "\".",
                "Find",
                JOptionPane.INFORMATION_MESSAGE);
        return false;
    }

    /**
     * Install case-insensitive type-ahead search support on a combo box.
     * Matching behavior mimics the pk3DS editors: the combo remains editable so the
     * user can type directly,
     * the dropdown stays open, and the closest matching entry is highlighted as
     * they type.
     */
    public static void installSearchableComboBox(JComboBox<?> comboBox) {
        if (comboBox == null) {
            return;
        }
        if (Boolean.TRUE.equals(comboBox.getClientProperty("EditorUtils.disableComboSearch"))) {
            return;
        }
        if (comboBox.getClientProperty(COMBOBOX_SEARCH_INSTALLED_KEY) != null) {
            return;
        }

        comboBox.setEditable(true);
        comboBox.putClientProperty("JComboBox.isTableCellEditor", Boolean.TRUE);
        Component editorComponent = comboBox.getEditor().getEditorComponent();
        if (!(editorComponent instanceof JTextComponent)) {
            comboBox.putClientProperty(COMBOBOX_SEARCH_INSTALLED_KEY, Boolean.TRUE);
            return;
        }

        ComboSearchSupport support = new ComboSearchSupport(comboBox, (JTextComponent) editorComponent);
        support.install();
        comboBox.putClientProperty(COMBOBOX_SEARCH_INSTALLED_KEY, support);
    }

    /**
     * Snapshot/revert guard for a TM-move or tutor-move assignment list (Feature #7).
     *
     * <p>The "Edit TM Moves..." / "Edit Tutor Moves..." dialogs apply their change to
     * the handler immediately (so the compatibility table headers can rebuild from the
     * live {@code getTMMoves()}/{@code getMoveTutorMoves()}). That breaks the editor's
     * Save/revert contract, where edits are supposed to persist only after Save and be
     * dropped on reload/close-without-save. This guard restores that contract cheaply:
     * the panel snapshots the original list at construction, marks the guard dirty when
     * the dialog applies a change, re-baselines on {@code save()}, and reverts the
     * handler from the snapshot on reload / close-without-save.</p>
     */
    public static final class MoveListGuard {
        private final java.util.function.Supplier<List<Integer>> getter;
        private final java.util.function.Consumer<List<Integer>> setter;
        private List<Integer> backup;
        private boolean dirty;

        public MoveListGuard(java.util.function.Supplier<List<Integer>> getter,
                java.util.function.Consumer<List<Integer>> setter) {
            this.getter = getter;
            this.setter = setter;
            this.backup = snapshot();
        }

        private List<Integer> snapshot() {
            List<Integer> current = getter != null ? getter.get() : null;
            return current != null ? new ArrayList<>(current) : null;
        }

        /** Records that the dialog applied a (possibly different) assignment to the handler. */
        public void markDirty() {
            dirty = true;
        }

        /** Re-baselines the snapshot to the current handler state (call from the panel's save()). */
        public void commit() {
            this.backup = snapshot();
            this.dirty = false;
        }

        /**
         * Reverts the handler to the snapshot if a dialog change is still unsaved.
         *
         * @return true if a revert actually happened (so the caller can rebuild headers).
         */
        public boolean revertIfDirty() {
            if (dirty && backup != null && setter != null) {
                setter.accept(new ArrayList<>(backup));
                dirty = false;
                return true;
            }
            dirty = false;
            return false;
        }
    }

    /**
     * Opens a modal dialog that lets the user reassign which move each TM teaches.
     *
     * <p>The dialog edits a working copy of {@code romHandler.getTMMoves()}; on OK
     * it builds a list of EXACTLY the original size (one move id per TM, in order),
     * clamping any out-of-range id and preserving the original id for any slot that
     * could not be resolved, calls {@code setTMMoves}, and logs the diff under the
     * "TM Moves" registry section. Returns {@code true} if the assignment was
     * applied and at least one TM changed, so the caller can refresh its headers.
     * Returns {@code false} on Cancel or when there are no TMs.</p>
     */
    public static boolean editTMMoves(Component parent, RomHandler romHandler) {
        if (romHandler == null) {
            return false;
        }
        List<Integer> current = romHandler.getTMMoves();
        if (current == null || current.isEmpty()) {
            return false;
        }
        String[] rowLabels = new String[current.size()];
        for (int i = 0; i < rowLabels.length; i++) {
            rowLabels[i] = String.format("TM%02d", i + 1);
        }
        return showMoveAssignmentDialog(parent, romHandler, "Edit TM Moves",
                rowLabels, current, romHandler::setTMMoves, "TM Moves");
    }

    /**
     * Opens a modal dialog that lets the user reassign which move each move tutor
     * teaches. Mirrors {@link #editTMMoves} but operates on
     * {@code getMoveTutorMoves()}/{@code setMoveTutorMoves} and logs under the
     * "Move Tutor Moves" registry section.
     *
     * <p>Note: some generations' {@code setMoveTutorMoves} is intentionally a
     * no-op (BW1) or rejects a size mismatch (BW2). The list passed in always has
     * the exact current size, so BW2 is satisfied; BW1 simply discards the call
     * without error, which is the engine's documented behaviour.</p>
     */
    public static boolean editMoveTutorMoves(Component parent, RomHandler romHandler) {
        if (romHandler == null || !romHandler.hasMoveTutors()) {
            return false;
        }
        List<Integer> current = romHandler.getMoveTutorMoves();
        if (current == null || current.isEmpty()) {
            return false;
        }
        String[] rowLabels = new String[current.size()];
        for (int i = 0; i < rowLabels.length; i++) {
            rowLabels[i] = String.format("Tutor %02d", i + 1);
        }
        return showMoveAssignmentDialog(parent, romHandler, "Edit Tutor Moves",
                rowLabels, current, romHandler::setMoveTutorMoves, "Move Tutor Moves");
    }

    /**
     * Shared implementation behind {@link #editTMMoves} and
     * {@link #editMoveTutorMoves}. Builds a table (read-only index column + a
     * searchable move combo per row), edits a working copy, and applies via the
     * supplied setter on OK.
     */
    private static boolean showMoveAssignmentDialog(Component parent, RomHandler romHandler, String title,
            String[] rowLabels, List<Integer> currentIds, java.util.function.Consumer<List<Integer>> setter,
            String registrySection) {
        // Effectively-final reference so the table model's anonymous class can capture it.
        final List<Move> moves = romHandler.getMoves() != null ? romHandler.getMoves() : new ArrayList<Move>();
        final int moveCount = moves.size();

        // Build the combo choices once: "id: name" per valid move. A blank/null move
        // entry is skipped. Selection is mapped back to a move id by parsing the
        // leading number, so a parallel id list is not required.
        List<String> choiceLabels = new ArrayList<>();
        for (int id = 0; id < moveCount; id++) {
            Move m = moves.get(id);
            if (m == null) {
                continue;
            }
            String name = (m.name != null && !m.name.isEmpty()) ? m.name : ("Move #" + id);
            choiceLabels.add(id + ": " + name);
        }
        if (choiceLabels.isEmpty()) {
            // No usable move names — nothing meaningful to choose from.
            return false;
        }
        final String[] choiceArray = choiceLabels.toArray(new String[0]);

        // Working copy of the current assignment; the dialog only mutates this.
        final int size = currentIds.size();
        final int[] working = new int[size];
        for (int i = 0; i < size; i++) {
            Integer v = currentIds.get(i);
            working[i] = v != null ? v : -1;
        }

        AbstractTableModel model = new AbstractTableModel() {
            @Override
            public int getRowCount() {
                return size;
            }

            @Override
            public int getColumnCount() {
                return 2;
            }

            @Override
            public String getColumnName(int column) {
                return column == 0 ? (title.contains("Tutor") ? "Tutor #" : "TM #") : "Move";
            }

            @Override
            public boolean isCellEditable(int rowIndex, int columnIndex) {
                return columnIndex == 1;
            }

            @Override
            public Object getValueAt(int rowIndex, int columnIndex) {
                if (columnIndex == 0) {
                    return rowLabels[rowIndex];
                }
                return labelForId(working[rowIndex]);
            }

            @Override
            public void setValueAt(Object aValue, int rowIndex, int columnIndex) {
                if (columnIndex != 1) {
                    return;
                }
                int id = parseLeadingId(aValue);
                if (id >= 0 && id < moveCount) {
                    working[rowIndex] = id;
                    fireTableCellUpdated(rowIndex, columnIndex);
                }
                // An unparseable/blank selection leaves the existing id untouched.
            }

            private String labelForId(int id) {
                if (id >= 0 && id < moveCount) {
                    Move m = moves.get(id);
                    String name = (m != null && m.name != null && !m.name.isEmpty()) ? m.name : ("Move #" + id);
                    return id + ": " + name;
                }
                return id + ": (invalid)";
            }
        };

        JTable table = new JTable(model);
        table.setRowHeight(24);
        table.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        table.getTableHeader().setReorderingAllowed(false);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        TableColumn indexColumn = table.getColumnModel().getColumn(0);
        indexColumn.setPreferredWidth(70);
        indexColumn.setMaxWidth(110);
        indexColumn.setMinWidth(60);

        TableColumn moveColumn = table.getColumnModel().getColumn(1);
        moveColumn.setPreferredWidth(260);
        JComboBox<String> combo = new JComboBox<>(choiceArray);
        combo.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        installSearchableComboBox(combo);
        moveColumn.setCellEditor(new DefaultCellEditor(combo));

        JScrollPane scroll = new JScrollPane(table);
        scroll.setPreferredSize(new Dimension(380, 420));

        Window owner = parent == null ? null : SwingUtilities.getWindowAncestor(parent);
        int result = JOptionPane.showConfirmDialog(owner != null ? owner : parent, scroll, title,
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        // Make sure an in-progress combo edit is committed before we read 'working'.
        if (table.isEditing()) {
            table.getCellEditor().stopCellEditing();
        }
        if (result != JOptionPane.OK_OPTION) {
            return false;
        }

        // Build the final list at EXACTLY the original size, in TM/tutor order.
        // Clamp invalid ids and fall back to the original id for any bad slot so
        // we never write a null/garbage move id.
        List<Integer> updated = new ArrayList<>(size);
        List<String> changeLog = new ArrayList<>();
        boolean changed = false;
        for (int i = 0; i < size; i++) {
            Integer originalBoxed = currentIds.get(i);
            int original = originalBoxed != null ? originalBoxed : -1;
            int chosen = working[i];
            if (chosen < 0 || chosen >= moveCount) {
                chosen = original; // keep original for unresolved/invalid slot
            }
            updated.add(chosen);
            if (chosen != original) {
                changed = true;
                changeLog.add(String.format("%s: %s -> %s",
                        rowLabels[i], moveNameForId(moves, original), moveNameForId(moves, chosen)));
            }
        }

        // Nothing to do if the user confirmed without changing any assignment;
        // skip the (size-preserving) setter so we don't trigger a redundant write.
        if (!changed) {
            return false;
        }
        setter.accept(updated);
        ManualEditRegistry.getInstance().addEntries(registrySection, changeLog);
        return true;
    }

    private static int parseLeadingId(Object value) {
        if (value == null) {
            return -1;
        }
        String text = value.toString().trim();
        int colon = text.indexOf(':');
        String token = colon >= 0 ? text.substring(0, colon).trim() : text;
        if (token.isEmpty()) {
            return -1;
        }
        try {
            return Integer.parseInt(token);
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    private static String moveNameForId(List<Move> moves, int id) {
        if (moves != null && id >= 0 && id < moves.size()) {
            Move m = moves.get(id);
            if (m != null && m.name != null && !m.name.isEmpty()) {
                return m.name;
            }
        }
        return "Move #" + id;
    }

    private static class ComboSearchSupport implements DocumentListener, FocusListener, ActionListener {
        private final JComboBox<?> comboBox;
        private final JTextComponent editor;
        private final List<Object> items = new ArrayList<>();
        private List<Object> currentItems;
        private boolean adjusting;
        private Object lastSelection;
        private String lastEditorText;
        private boolean userHasTyped;

        ComboSearchSupport(JComboBox<?> comboBox, JTextComponent editor) {
            this.comboBox = comboBox;
            this.editor = editor;

            ComboBoxModel<?> model = comboBox.getModel();
            for (int i = 0; i < model.getSize(); i++) {
                items.add(model.getElementAt(i));
            }

            currentItems = new ArrayList<>(items);
            lastSelection = comboBox.getSelectedItem();
            lastEditorText = editor.getText();
            userHasTyped = false;
        }

        void install() {
            editor.getDocument().addDocumentListener(this);
            editor.addFocusListener(this);
            comboBox.addActionListener(this);
        }

        @Override
        public void insertUpdate(DocumentEvent e) {
            handleDocumentChange();
        }

        @Override
        public void removeUpdate(DocumentEvent e) {
            handleDocumentChange();
        }

        @Override
        public void changedUpdate(DocumentEvent e) {
            handleDocumentChange();
        }

        private void handleDocumentChange() {
            if (adjusting) {
                return;
            }
            userHasTyped = true;
            SwingUtilities.invokeLater(() -> updateSelection(true));
        }

        private void updateSelection(boolean forcePopup) {
            if (adjusting) {
                return;
            }

            String query = editor.getText();
            if (query == null) {
                query = "";
            }
            String filterText = userHasTyped ? query : "";
            adjusting = true;
            updateModelForQuery(filterText);
            applyEditorText(query, query.length());
            if ((forcePopup || !(query.isEmpty())) && comboBox.isDisplayable()) {
                comboBox.setPopupVisible(true);
            }
            adjusting = false;
            lastEditorText = editor.getText();
        }

        private void selectItemIfChanged(Object item) {
            if (!Objects.equals(comboBox.getSelectedItem(), item)) {
                comboBox.setSelectedItem(item);
            }
        }

        private void applyEditorText(String text, int typedLength) {
            editor.setText(text);
            int len = text.length();
            int highlightStart = Math.max(0, Math.min(typedLength, len));
            if (highlightStart < len) {
                editor.setCaretPosition(highlightStart);
                editor.moveCaretPosition(len);
            } else {
                editor.setCaretPosition(len);
            }
        }

        @Override
        public void focusGained(FocusEvent e) {
            SwingUtilities.invokeLater(() -> updateSelection(true));
        }

        @Override
        public void focusLost(FocusEvent e) {
            SwingUtilities.invokeLater(this::commitSelection);
        }

        private void commitSelection() {
            if (adjusting) {
                return;
            }

            adjusting = true;
            Object selected = comboBox.getSelectedItem();
            if (selected == null) {
                selected = findExactMatch(editor.getText());
            }
            if (selected == null) {
                selected = lastSelection;
            } else {
                lastSelection = selected;
            }

            if (selected != null) {
                selectItemIfChanged(selected);
                applyEditorText(selected.toString(), selected.toString().length());
            } else {
                applyEditorText("", 0);
            }

            comboBox.setPopupVisible(false);
            restoreAllItems();
            adjusting = false;
            lastEditorText = editor.getText();
            userHasTyped = false;
        }

        private Object findExactMatch(String text) {
            if (text == null) {
                return null;
            }
            String lower = text.toLowerCase(Locale.ROOT);
            for (Object item : items) {
                if (item == null) {
                    if (lower.isEmpty()) {
                        return item;
                    }
                    continue;
                }
                String label = item.toString();
                if (label != null && label.toLowerCase(Locale.ROOT).equals(lower)) {
                    return item;
                }
            }
            return null;
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            if (adjusting) {
                return;
            }
            Object selected = comboBox.getSelectedItem();
            adjusting = true;
            if (selected != null) {
                lastSelection = selected;
                applyEditorText(selected.toString(), selected.toString().length());
            } else {
                applyEditorText("", 0);
            }
            restoreAllItems();
            adjusting = false;
            lastEditorText = editor.getText();
            userHasTyped = false;
        }

        private void updateModelForQuery(String query) {
            List<Object> matches = getMatches(query);
            if (!matches.equals(currentItems)) {
                currentItems = new ArrayList<>(matches);
                @SuppressWarnings({ "rawtypes", "unchecked" })
                DefaultComboBoxModel model = new DefaultComboBoxModel(currentItems.toArray());
                comboBox.setModel(model);
            }
            comboBox.getEditor().setItem(query);
        }

        private List<Object> getMatches(String query) {
            if (query == null || query.isEmpty()) {
                return new ArrayList<>(items);
            }

            String lower = query.toLowerCase(Locale.ROOT);
            LinkedHashSet<Object> ordered = new LinkedHashSet<>();

            for (Object item : items) {
                if (item == null) {
                    continue;
                }
                String label = item.toString();
                if (label == null) {
                    continue;
                }
                if (label.toLowerCase(Locale.ROOT).startsWith(lower)) {
                    ordered.add(item);
                }
            }

            for (Object item : items) {
                if (item == null) {
                    continue;
                }
                String label = item.toString();
                if (label == null) {
                    continue;
                }
                String lowerLabel = label.toLowerCase(Locale.ROOT);
                if (!lowerLabel.startsWith(lower) && lowerLabel.contains(lower)) {
                    ordered.add(item);
                }
            }

            if (ordered.isEmpty()) {
                return new ArrayList<>(items);
            }

            return new ArrayList<>(ordered);
        }

        private void restoreAllItems() {
            if (currentItems == null || currentItems.size() == items.size()) {
                return;
            }
            currentItems = new ArrayList<>(items);
            @SuppressWarnings({ "rawtypes", "unchecked" })
            DefaultComboBoxModel model = new DefaultComboBoxModel(currentItems.toArray());
            comboBox.setModel(model);
            Object text = editor.getText();
            if (text != null) {
                comboBox.getEditor().setItem(text);
            }
            if (lastSelection != null) {
                selectItemIfChanged(lastSelection);
            }
            userHasTyped = false;
        }
    }

    private static void selectCell(JTable frozenTable, JTable mainTable, int frozenColumns, int row, int column) {
        if (mainTable != null) {
            runWithFrozenSyncSuppressed(mainTable,
                    () -> performSelectCell(frozenTable, mainTable, frozenColumns, row, column));
        } else {
            performSelectCell(frozenTable, null, frozenColumns, row, column);
        }
    }

    private static void performSelectCell(JTable frozenTable, JTable mainTable, int frozenColumns, int row,
            int column) {
        selectRow(frozenTable, row);
        selectRow(mainTable, row);

        if (column < frozenColumns && frozenTable != null) {
            selectFullRowColumns(frozenTable);
            scrollRowIntoView(frozenTable, row, column);
            if (mainTable != null) {
                if (mainTable.getColumnCount() > 0) {
                    selectFullRowColumns(mainTable);
                    scrollRowIntoView(mainTable, row, 0);
                } else {
                    clearColumnSelection(mainTable);
                }
                mainTable.requestFocusInWindow();
            }
            frozenTable.requestFocusInWindow();
        } else if (mainTable != null) {
            int mainColumn = column - frozenColumns;
            if (mainTable.getColumnCount() > 0) {
                selectColumn(mainTable, mainColumn);
                scrollRowIntoView(mainTable, row, mainColumn);
            } else {
                clearColumnSelection(mainTable);
            }
            if (frozenTable != null) {
                selectFullRowColumns(frozenTable);
                scrollRowIntoView(frozenTable, row, 0);
            }
            mainTable.requestFocusInWindow();
        }
    }

    private static void selectRow(JTable table, int row) {
        if (table == null || table.getRowCount() == 0) {
            return;
        }
        int safeRow = Math.max(0, Math.min(row, table.getRowCount() - 1));
        table.setRowSelectionInterval(safeRow, safeRow);
    }

    private static void selectColumn(JTable table, int column) {
        if (table == null) {
            return;
        }
        int columnCount = table.getColumnCount();
        if (columnCount <= 0) {
            table.getColumnModel().getSelectionModel().clearSelection();
            return;
        }
        int safeColumn = Math.max(0, Math.min(column, columnCount - 1));
        table.getColumnModel().getSelectionModel().setSelectionInterval(safeColumn, safeColumn);
    }

    private static void selectFullRowColumns(JTable table) {
        if (table == null) {
            return;
        }
        int columnCount = table.getColumnCount();
        if (columnCount <= 0) {
            table.getColumnModel().getSelectionModel().clearSelection();
            return;
        }
        table.getColumnModel().getSelectionModel().setSelectionInterval(0, columnCount - 1);
    }

    private static void clearColumnSelection(JTable table) {
        if (table == null) {
            return;
        }
        table.getColumnModel().getSelectionModel().clearSelection();
    }

    private static void scrollRowIntoView(JTable table, int row, int column) {
        if (table == null || table.getRowCount() == 0) {
            return;
        }
        int safeRow = Math.max(0, Math.min(row, table.getRowCount() - 1));
        int columnCount = table.getColumnCount();
        if (columnCount <= 0) {
            int rowHeight = table.getRowHeight(safeRow);
            Rectangle fallback = new Rectangle(0, safeRow * rowHeight, 1, rowHeight);
            table.scrollRectToVisible(fallback);
            return;
        }
        int safeColumn = Math.max(0, Math.min(column, columnCount - 1));
        Rectangle cellRect = table.getCellRect(safeRow, safeColumn, true);
        table.scrollRectToVisible(cellRect);
    }

    public static void installFrozenColumnSync(JTable frozenTable, JTable mainTable) {
        if (frozenTable == null || mainTable == null ||
                frozenTable.getColumnModel() == null || mainTable.getColumnModel() == null) {
            return;
        }

        setFullRowSelectionActive(frozenTable, false);
        ListSelectionModel frozenColumnModel = frozenTable.getColumnModel().getSelectionModel();
        ListSelectionModel mainColumnModel = mainTable.getColumnModel().getSelectionModel();

        mainTable.putClientProperty(SUPPRESS_FROZEN_SYNC_KEY, Boolean.FALSE);

        mainTable.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mousePressed(java.awt.event.MouseEvent e) {
                if (!isFullRowSelection(mainTable)) {
                    frozenColumnModel.clearSelection();
                    setFullRowSelectionActive(frozenTable, false);
                }
            }
        });

        mainColumnModel.addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) {
                return;
            }
            if (Boolean.TRUE.equals(mainTable.getClientProperty(SUPPRESS_FROZEN_SYNC_KEY))) {
                return;
            }
            if (!isFullRowSelection(mainTable)) {
                frozenColumnModel.clearSelection();
                setFullRowSelectionActive(frozenTable, false);
            }
        });
    }

    /**
     * Keeps two scroll panes (typically frozen + main tables) vertically aligned.
     * Shares the vertical scroll bar model and mirrors viewport Y positions so the
     * frozen columns stay in sync even at scroll extremes.
     */
    public static void linkVerticalScrollBars(JScrollPane frozenScrollPane, JScrollPane mainScrollPane) {
        if (frozenScrollPane == null || mainScrollPane == null) {
            return;
        }
        JScrollBar mainVertical = mainScrollPane.getVerticalScrollBar();
        JScrollBar frozenVertical = frozenScrollPane.getVerticalScrollBar();
        if (mainVertical == null || frozenVertical == null) {
            return;
        }
        frozenVertical.setModel(mainVertical.getModel());

        JViewport mainViewport = mainScrollPane.getViewport();
        JViewport frozenViewport = frozenScrollPane.getViewport();
        if (mainViewport == null || frozenViewport == null) {
            return;
        }

        final boolean[] updating = new boolean[1];
        Runnable syncFromMain = () -> {
            if (updating[0]) {
                return;
            }
            Point mainPos = mainViewport.getViewPosition();
            Point frozenPos = frozenViewport.getViewPosition();
            if (frozenPos.y != mainPos.y) {
                updating[0] = true;
                frozenViewport.setViewPosition(new Point(frozenPos.x, mainPos.y));
                updating[0] = false;
            }
        };

        mainViewport.addChangeListener(e -> syncFromMain.run());
        frozenViewport.addChangeListener(e -> {
            if (updating[0]) {
                return;
            }
            Point frozenPos = frozenViewport.getViewPosition();
            Point mainPos = mainViewport.getViewPosition();
            if (mainPos.y != frozenPos.y) {
                updating[0] = true;
                mainViewport.setViewPosition(new Point(mainPos.x, frozenPos.y));
                updating[0] = false;
            }
        });
        syncFromMain.run();
    }

    /**
     * Adds a spacer beneath the frozen table so the horizontal scrollbar visually spans the full width
     * when it appears.
     */
    public static void addHorizontalScrollbarSpacer(JPanel frozenContainer, JScrollPane mainScrollPane) {
        if (frozenContainer == null || mainScrollPane == null) {
            return;
        }
        JScrollBar horizontalBar = mainScrollPane.getHorizontalScrollBar();
        if (horizontalBar == null) {
            return;
        }
        JPanel spacer = new JPanel();
        spacer.setOpaque(true);
        spacer.setBackground(horizontalBar.getBackground());
        int barHeight = horizontalBar.getPreferredSize().height;
        spacer.setPreferredSize(new Dimension(0, barHeight));
        spacer.setVisible(horizontalBar.isVisible());

        horizontalBar.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentShown(ComponentEvent e) {
                spacer.setVisible(true);
            }

            @Override
            public void componentHidden(ComponentEvent e) {
                spacer.setVisible(false);
            }
        });

        frozenContainer.add(spacer, BorderLayout.SOUTH);
    }

    public static void runWithFrozenSyncSuppressed(JTable mainTable, Runnable action) {
        if (action == null) {
            return;
        }
        if (mainTable == null) {
            action.run();
            return;
        }
        Object previous = mainTable.getClientProperty(SUPPRESS_FROZEN_SYNC_KEY);
        mainTable.putClientProperty(SUPPRESS_FROZEN_SYNC_KEY, Boolean.TRUE);
        try {
            action.run();
        } finally {
            if (previous == null) {
                mainTable.putClientProperty(SUPPRESS_FROZEN_SYNC_KEY, Boolean.FALSE);
            } else {
                mainTable.putClientProperty(SUPPRESS_FROZEN_SYNC_KEY, previous);
            }
        }
    }

    private static boolean isFullRowSelection(JTable table) {
        if (table == null) {
            return false;
        }
        int columnCount = table.getColumnCount();
        if (columnCount <= 0) {
            return false;
        }
        ListSelectionModel columnSelectionModel = table.getColumnModel().getSelectionModel();
        if (columnSelectionModel == null || columnSelectionModel.isSelectionEmpty()) {
            return false;
        }
        return columnSelectionModel.getMinSelectionIndex() == 0
                && columnSelectionModel.getMaxSelectionIndex() == columnCount - 1;
    }

    public static void setFullRowSelectionActive(JTable frozenTable, boolean active) {
        if (frozenTable == null) {
            return;
        }
        frozenTable.putClientProperty(FULL_ROW_SELECTION_ACTIVE_KEY, active);
    }

    public static boolean isFullRowSelectionActive(JTable frozenTable) {
        return frozenTable != null && Boolean.TRUE.equals(frozenTable.getClientProperty(FULL_ROW_SELECTION_ACTIVE_KEY));
    }

    public static void installHeaderViewportSync(JScrollPane scrollPane) {
        if (scrollPane == null) {
            return;
        }
        JViewport viewport = scrollPane.getViewport();
        JViewport columnHeader = scrollPane.getColumnHeader();
        if (viewport == null || columnHeader == null) {
            return;
        }

        ChangeListener listener = e -> syncHeaderViewport(scrollPane);
        viewport.addChangeListener(listener);

        JScrollBar horizontal = scrollPane.getHorizontalScrollBar();
        if (horizontal != null) {
            horizontal.addAdjustmentListener(e -> syncHeaderViewport(scrollPane));
        }

        syncHeaderViewport(scrollPane);
    }

    private static void syncHeaderViewport(JScrollPane scrollPane) {
        if (scrollPane == null) {
            return;
        }
        JViewport viewport = scrollPane.getViewport();
        JViewport columnHeader = scrollPane.getColumnHeader();
        if (viewport == null || columnHeader == null) {
            return;
        }
        Point viewPosition = viewport.getViewPosition();
        Point headerPosition = columnHeader.getViewPosition();
        if (headerPosition.x != viewPosition.x) {
            columnHeader.setViewPosition(new Point(viewPosition.x, headerPosition.y));
        }
    }

    /**
     * Sentinel returned by {@link #coerceValue} when a cell value cannot be
     * coerced to the column's expected type (e.g. a non-numeric/empty string for
     * an Integer column). Callers skip the cell instead of writing it, so the
     * model keeps its existing value rather than silently defaulting to 0.
     */
    static final Object UNPARSEABLE_CELL = new Object();

    private static Object coerceValue(String rawValue, Class<?> columnClass) {
        if (columnClass == null) {
            return rawValue;
        }

        if (columnClass == Boolean.class || columnClass == boolean.class) {
            return parseBooleanCell(rawValue);
        }

        if (columnClass == Integer.class || columnClass == int.class) {
            // Validate Integer columns up front. Previously the raw string was
            // forwarded to the model, whose parseInt swallowed errors and turned
            // a typo'd/empty cell silently into 0 (then clamped to the column min),
            // corrupting the stat. Flag the failure so the caller can skip it.
            String trimmed = rawValue == null ? "" : rawValue.trim();
            try {
                return Integer.parseInt(trimmed);
            } catch (NumberFormatException ex) {
                return UNPARSEABLE_CELL;
            }
        }

        return rawValue;
    }

    private static boolean parseBooleanCell(String value) {
        if (value == null) {
            return false;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("true") || normalized.equals("1") || normalized.equals("yes");
    }

    /**
     * Quote-aware parse of an entire CSV document into rows. A record terminator
     * ('\n', '\r', or '\r\n') only ends a row when it occurs outside quotes; a
     * newline inside a quoted field is preserved as part of that field's value.
     * This makes import symmetric with escapeCSV's export quoting of newline-
     * bearing cells, so a round-trip of such a cell no longer splits/corrupts rows.
     */
    private static List<String[]> parseCsvContent(String content) {
        List<String[]> rows = new ArrayList<>();
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        boolean rowHasContent = false;

        for (int i = 0; i < content.length(); i++) {
            char ch = content.charAt(i);
            if (ch == '"') {
                if (inQuotes && i + 1 < content.length() && content.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
                rowHasContent = true;
            } else if (ch == ',' && !inQuotes) {
                values.add(current.toString());
                current.setLength(0);
                rowHasContent = true;
            } else if ((ch == '\n' || ch == '\r') && !inQuotes) {
                // End of record. Collapse a "\r\n" pair into a single terminator.
                if (ch == '\r' && i + 1 < content.length() && content.charAt(i + 1) == '\n') {
                    i++;
                }
                values.add(current.toString());
                current.setLength(0);
                rows.add(values.toArray(new String[0]));
                values = new ArrayList<>();
                rowHasContent = false;
            } else {
                current.append(ch);
                rowHasContent = true;
            }
        }

        // Flush any trailing record that is not terminated by a final newline.
        if (rowHasContent || current.length() > 0 || !values.isEmpty()) {
            values.add(current.toString());
            rows.add(values.toArray(new String[0]));
        }

        return rows;
    }

    private static String stripBom(String value) {
        if (value != null && !value.isEmpty() && value.charAt(0) == '\ufeff') {
            return value.substring(1);
        }
        return value;
    }

    /**
     * Encapsulates CSV data selected by the user.
     */
    public static class CsvData {
        private final File file;
        private final List<String[]> rows;

        public CsvData(File file, List<String[]> rows) {
            this.file = file;
            this.rows = rows;
        }

        public File getFile() {
            return file;
        }

        public List<String[]> getRows() {
            return rows;
        }
    }

    /**
     * Find dialog configuration.
     */
    public static class FindOptions {
        private final String query;
        private final boolean matchCase;
        private final boolean matchEntireCell;

        public FindOptions(String query, boolean matchCase, boolean matchEntireCell) {
            this.query = query;
            this.matchCase = matchCase;
            this.matchEntireCell = matchEntireCell;
        }

        public String getQuery() {
            return query;
        }

        public boolean isMatchCase() {
            return matchCase;
        }

        public boolean isMatchEntireCell() {
            return matchEntireCell;
        }

        private boolean sameSearch(FindOptions other) {
            return other != null
                    && matchCase == other.matchCase
                    && matchEntireCell == other.matchEntireCell
                    && query.equals(other.query);
        }
    }

    /**
     * Stores state for repeated find operations.
     */
    public static class FindState {
        private FindOptions lastOptions;
        private int lastRow = -1;
        private int lastCol = -1;

        public boolean canContinueWith(FindOptions options) {
            return lastOptions != null
                    && lastRow >= 0
                    && lastCol >= 0
                    && lastOptions.sameSearch(options);
        }

        public int getLastIndex(int totalColumns) {
            return lastRow * totalColumns + lastCol;
        }

        public void update(FindOptions options, int row, int col) {
            this.lastOptions = options;
            this.lastRow = row;
            this.lastCol = col;
        }

        public FindOptions getLastOptions() {
            return lastOptions;
        }
    }
}
