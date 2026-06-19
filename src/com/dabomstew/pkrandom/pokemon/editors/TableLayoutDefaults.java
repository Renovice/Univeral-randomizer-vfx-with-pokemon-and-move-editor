package com.dabomstew.pkrandom.pokemon.editors;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumn;
import javax.swing.table.TableColumnModel;
import javax.swing.table.JTableHeader;
import java.awt.*;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Centralised styling defaults for editor tables so that all sheets share a
 * consistent look.
 */
final class TableLayoutDefaults {

    static final Font CELL_FONT = new Font("Segoe UI", Font.PLAIN, 12);
    static final Font HEADER_FONT = new Font("Segoe UI", Font.BOLD, 11);
    static final int HEADER_HEIGHT = 60;

    // Colors are resolved through EditorTheme at call time so the sheets
    // follow the light/dark theme. Renderers calling these per paint pick up
    // a theme switch live; values captured at construction apply on reopen.
    static Color headerBg() {
        return EditorTheme.headerBg();
    }

    static Color headerFg() {
        return EditorTheme.strongText();
    }

    static Color gridColor() {
        return EditorTheme.grid();
    }

    static Color selectionBg() {
        return EditorTheme.selectionBg();
    }

    static Color selectionFg() {
        return EditorTheme.selectionFg();
    }

    static Color evenRowColor() {
        return EditorTheme.surface();
    }

    static Color oddRowColor() {
        return EditorTheme.altRow();
    }

    static Color frozenRowColor() {
        return EditorTheme.frozenRow();
    }

    private static final int ROW_HEIGHT_ICON = 64;
    private static final int ROW_HEIGHT_TEXT = 52;

    private static final int FROZEN_ID_WIDTH = 70;
    private static final int FROZEN_NAME_WIDTH_WITH_ICON = 260;
    private static final int FROZEN_NAME_WIDTH_NO_ICON = 220;

    private TableLayoutDefaults() {
        // Utility class
    }

    static void applyBaseTableSettings(JTable table) {
        table.setFont(CELL_FONT);
        table.setShowGrid(true);
        table.setGridColor(gridColor());
        table.setBackground(EditorTheme.surface());
        table.setForeground(EditorTheme.text());
        table.setSelectionBackground(selectionBg());
        table.setSelectionForeground(selectionFg());
        table.setFillsViewportHeight(true);
        table.setIntercellSpacing(new Dimension(1, 1));
        table.setCellSelectionEnabled(true);
        table.setRowSelectionAllowed(true);
        table.setColumnSelectionAllowed(true);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
    }

    static void applyRowHeight(JTable table, boolean hasIcons) {
        table.setRowHeight(hasIcons ? ROW_HEIGHT_ICON : ROW_HEIGHT_TEXT);
    }

    static void configureFrozenColumns(TableColumnModel model, boolean hasIcons) {
        if (model == null || model.getColumnCount() < 2) {
            return;
        }
        TableColumn idColumn = model.getColumn(0);
        idColumn.setPreferredWidth(FROZEN_ID_WIDTH);
        idColumn.setMinWidth(FROZEN_ID_WIDTH);
        idColumn.setMaxWidth(FROZEN_ID_WIDTH + 10);

        TableColumn nameColumn = model.getColumn(1);
        int width = hasIcons ? FROZEN_NAME_WIDTH_WITH_ICON : FROZEN_NAME_WIDTH_NO_ICON;
        nameColumn.setPreferredWidth(width);
        nameColumn.setMinWidth(width);
        nameColumn.setMaxWidth(width + 40);
    }

    /**
     * Configure column widths for Gen 6 Personal Sheet table
     * Gen 6 has 36 columns total (minus 2 frozen = 34 visible columns)
     */
    static void configureGen6PersonalColumns(TableColumnModel model) {
        if (model == null) {
            return;
        }
        // Configure widths for Gen 6 personal data columns
        // Columns: HP, ATK, DEF, SPEED, SP_ATK, SP_DEF, Type1, Type2, Catch Rate, Exp Yield,
        // EV Yields (6), Held Items (4), Gender Ratio, Hatch Counter, Base Happiness, Growth Rate,
        // Egg Groups (2), Abilities (3), Run Chance, Height, Weight, Color, Flip

        int[] widths = {
            70, 70, 70, 70, 70, 70,  // Base stats
            100, 100,                 // Types
            90, 90,                   // Catch rate, Exp yield
            80, 80, 80, 80, 80, 80,   // EV yields
            150, 150, 150, 150,       // Held items (Common, Rare, Guaranteed, Very Rare)
            100, 100, 110, 130,       // Gender, Hatch, Happiness, Growth
            120, 120,                 // Egg groups
            130, 130, 130,            // Abilities
            90, 80, 80, 80, 70        // Run chance, Height, Weight, Color, Flip
        };

        for (int i = 0; i < model.getColumnCount() && i < widths.length; i++) {
            TableColumn column = model.getColumn(i);
            column.setPreferredWidth(widths[i]);
            column.setMinWidth(widths[i] - 20);
            column.setMaxWidth(widths[i] + 100);
        }
    }

    static void configureHeader(JTableHeader header) {
        if (header == null) {
            return;
        }
        header.setFont(HEADER_FONT);
        header.setBackground(headerBg());
        header.setForeground(headerFg());
        header.setBorder(BorderFactory.createMatteBorder(0, 0, 2, 0, EditorTheme.border()));
        header.setReorderingAllowed(false);
        Dimension pref = header.getPreferredSize();
        header.setPreferredSize(new Dimension(pref != null ? pref.width : 0, HEADER_HEIGHT));
    }

    static void applySheetTableStyle(JTable table, boolean isFrozen, int... leftAlignedColumns) {
        applyBaseTableSettings(table);
        JTableHeader header = table.getTableHeader();
        configureHeader(header);
        if (header != null) {
            header.setDefaultRenderer(createMultiLineHeaderRenderer());
        }
        installStripedRenderers(table, isFrozen, leftAlignedColumns);
    }

    static void installStripedRenderers(JTable table, boolean isFrozen, int... leftAlignedColumns) {
        TableCellRenderer renderer = new StripedCellRenderer(isFrozen, leftAlignedColumns);
        table.setDefaultRenderer(Object.class, renderer);
        table.setDefaultRenderer(String.class, renderer);
        table.setDefaultRenderer(Integer.class, renderer);
        table.setDefaultRenderer(Number.class, renderer);
    }

    static TableCellRenderer createMultiLineHeaderRenderer() {
        return new MultiLineHeaderRenderer();
    }

    private static class MultiLineHeaderRenderer extends JLabel implements TableCellRenderer {
        MultiLineHeaderRenderer() {
            setOpaque(true);
            setHorizontalAlignment(SwingConstants.CENTER);
            setVerticalAlignment(SwingConstants.CENTER);
            setFont(HEADER_FONT);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                boolean isSelected, boolean hasFocus, int row, int column) {
            // Colors resolved per paint so headers follow theme switches live.
            setBackground(headerBg());
            setForeground(headerFg());
            setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, 1, 1, EditorTheme.border()),
                    new EmptyBorder(6, 6, 6, 6)));
            if (value != null) {
                String text = value.toString();
                if (!text.startsWith("<html>")) {
                    text = text.replace("\n", "<br>");
                    text = "<html><center>" + text + "</center></html>";
                }
                setText(text);
            } else {
                setText("");
            }
            return this;
        }
    }

    static int frozenPanelWidth(boolean hasIcons) {
        return FROZEN_ID_WIDTH + (hasIcons ? FROZEN_NAME_WIDTH_WITH_ICON : FROZEN_NAME_WIDTH_NO_ICON);
    }

    static void refreshHeaderPreferredWidth(JTable table) {
        if (table == null) {
            return;
        }
        SwingUtilities.invokeLater(() -> {
            JTableHeader header = table.getTableHeader();
            if (header == null) {
                return;
            }
            TableColumnModel columnModel = table.getColumnModel();
            if (columnModel == null) {
                return;
            }
            int tableWidth = Math.max(table.getPreferredSize().width, columnModel.getTotalColumnWidth());
            if (tableWidth <= 0) {
                return;
            }
            Dimension current = header.getPreferredSize();
            if (current == null || current.width != tableWidth || current.height != HEADER_HEIGHT) {
                header.setPreferredSize(new Dimension(tableWidth, HEADER_HEIGHT));
                header.revalidate();
            }
        });
    }

    static class StripedCellRenderer extends DefaultTableCellRenderer {
        private final boolean isFrozen;
        private final Set<Integer> leftAlignedColumns;

        StripedCellRenderer(boolean isFrozen, int... leftAlignedColumns) {
            this.isFrozen = isFrozen;
            this.leftAlignedColumns = new HashSet<>();
            if (leftAlignedColumns != null) {
                Arrays.stream(leftAlignedColumns)
                        .filter(index -> index >= 0)
                        .forEach(this.leftAlignedColumns::add);
            }
            setOpaque(true);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                boolean isSelected, boolean hasFocus, int row, int column) {
            Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);

            if (leftAlignedColumns.contains(column)) {
                setHorizontalAlignment(SwingConstants.LEFT);
            } else {
                setHorizontalAlignment(SwingConstants.CENTER);
            }

            if (!isSelected) {
                if (isFrozen) {
                    c.setBackground(frozenRowColor());
                } else {
                    c.setBackground(row % 2 == 0 ? evenRowColor() : oddRowColor());
                }
                c.setForeground(EditorTheme.text());
            }

            setBorder(noFocusBorder);
            return c;
        }
    }
}
