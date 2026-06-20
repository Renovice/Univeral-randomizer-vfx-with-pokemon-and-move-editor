package com.dabomstew.pkrandom.gui;

import com.dabomstew.pkromio.gamedata.Effectiveness;
import com.dabomstew.pkromio.gamedata.TypeTable;

import javax.swing.*;
import javax.swing.Box;
import javax.swing.border.EmptyBorder;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumn;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Image;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import javax.imageio.ImageIO;

class TypeEffectivenessEditorDialog extends JDialog {
    // The full ordered set of values, ZERO (immunity) first. Whether ZERO is actually offered
    // depends on the loaded ROM's capability (see allowZero / ALLOWED_EFFECTIVENESSES).
    private static final Effectiveness[] EFFECTIVENESSES_WITH_ZERO = new Effectiveness[] {
            Effectiveness.ZERO, Effectiveness.HALF, Effectiveness.NEUTRAL, Effectiveness.DOUBLE };
    private static final Effectiveness[] EFFECTIVENESSES_WITHOUT_ZERO = new Effectiveness[] {
            Effectiveness.HALF, Effectiveness.NEUTRAL, Effectiveness.DOUBLE };

    // Selectable values for this dialog instance: includes ZERO only when the ROM can persist
    // newly-added immunities (RomHandler.hasTypeImmunityEditSupport()).
    private final Effectiveness[] allowedEffectivenesses;

    private final ResourceBundle bundle;
    private final TypeTable originalTable;
    private final TypeTable workingTable;
    private final List<com.dabomstew.pkromio.gamedata.Type> types;
    private final Map<com.dabomstew.pkromio.gamedata.Type, ImageIcon> typeIcons;

    private CardLayout viewLayout;
    private JPanel viewPanel;
    private JTable effectivenessTable;
    private TypeEffectivenessTableModel tableModel;
    private JLabel detailTypeIconLabel;
    private JLabel detailTypeNameLabel;
    private JPanel attackGrid;
    private JPanel defenseGrid;
    private com.dabomstew.pkromio.gamedata.Type currentDetailType;

    private boolean saved;
    private TypeTable resultTable;

    TypeEffectivenessEditorDialog(java.awt.Window owner, ResourceBundle bundle, TypeTable sourceTable) {
        // Default: allow ZERO (immunity). Callers that know the ROM cannot persist new immunities
        // should use the 4-arg constructor with allowZero=false.
        this(owner, bundle, sourceTable, true);
    }

    TypeEffectivenessEditorDialog(java.awt.Window owner, ResourceBundle bundle, TypeTable sourceTable,
            boolean allowZero) {
        super(owner, bundle.getString("GUI.teManualEditorDialog.title"), ModalityType.APPLICATION_MODAL);
        this.bundle = bundle;
        this.allowedEffectivenesses = allowZero ? EFFECTIVENESSES_WITH_ZERO : EFFECTIVENESSES_WITHOUT_ZERO;
        this.originalTable = new TypeTable(sourceTable);
        this.workingTable = new TypeTable(sourceTable);
        this.types = workingTable.getTypes();
        this.typeIcons = loadTypeIcons();
        initUI();
        setMinimumSize(new Dimension(720, 480));
        pack();
        // Force the dialog to be a specific size and center it
        Dimension screenSize = java.awt.Toolkit.getDefaultToolkit().getScreenSize();
        int width = Math.min(1600, screenSize.width - 100);
        int height = Math.min(950, screenSize.height - 100);
        setBounds((screenSize.width - width) / 2, (screenSize.height - height) / 2, width, height);
    }

    boolean wasSaved() {
        return saved;
    }

    TypeTable getResultTable() {
        return resultTable;
    }

    private Map<com.dabomstew.pkromio.gamedata.Type, ImageIcon> loadTypeIcons() {
        Map<com.dabomstew.pkromio.gamedata.Type, ImageIcon> icons = new HashMap<>();
        for (com.dabomstew.pkromio.gamedata.Type type : com.dabomstew.pkromio.gamedata.Type.values()) {
            // Convert type name to capitalized format (e.g., NORMAL -> Normal)
            String typeName = type.name().substring(0, 1).toUpperCase() + type.name().substring(1).toLowerCase();
            String iconPath = "/com/dabomstew/pkromio/graphics/resources/type_icons/" + typeName + ".png";
            try (InputStream in = getClass().getResourceAsStream(iconPath)) {
                if (in != null) {
                    Image img = ImageIO.read(in);
                    if (img != null) {
                        icons.put(type, new ImageIcon(img));
                    }
                }
                // Missing resource (in == null): fall back to color-only display.
            } catch (IOException | IllegalArgumentException e) {
                // Icon not found, will fall back to color-only display
                System.err.println("Warning: Could not load type icon for " + type.name() + ": " + e.getMessage());
            }
        }
        return icons;
    }

    private void initUI() {
        setLayout(new BorderLayout(10, 10));

        tableModel = new TypeEffectivenessTableModel();
        effectivenessTable = new JTable(tableModel) {
            @Override
            public String getToolTipText(java.awt.event.MouseEvent event) {
                int row = rowAtPoint(event.getPoint());
                int column = columnAtPoint(event.getPoint());
                if (row >= 0 && column >= 0) {
                    Object value = getValueAt(row, column);
                    if (value instanceof Effectiveness) {
                        return effectDescription((Effectiveness) value);
                    }
                }
                return super.getToolTipText(event);
            }
        };
        effectivenessTable.setRowSelectionAllowed(false);
        effectivenessTable.setCellSelectionEnabled(true);
        effectivenessTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        effectivenessTable.setRowHeight(60); // Increased from 26 to 60 for better icon display
        effectivenessTable.setShowGrid(true); // Show grid
        effectivenessTable.setGridColor(Color.BLACK); // Black gridlines
        effectivenessTable.setIntercellSpacing(new Dimension(1, 1)); // 1 pixel spacing
        effectivenessTable.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        effectivenessTable.setDefaultRenderer(Effectiveness.class, new EffectivenessCellRenderer());
        effectivenessTable.getTableHeader().setReorderingAllowed(false);

        JComboBox<Effectiveness> effectivenessCombo = new JComboBox<>(allowedEffectivenesses);
        effectivenessCombo.setRenderer(new EffectivenessComboRenderer());
        effectivenessTable.setDefaultEditor(Effectiveness.class, new DefaultCellEditor(effectivenessCombo));

        // The defending-type column lives in a separate frozen table placed in
        // the scroll pane's row header, so it stays put when scrolling
        // horizontally (mirroring how the attacking-type header row stays put
        // when scrolling vertically).
        final JTable defenderTable = new JTable(tableModel);
        defenderTable.setRowHeight(60);
        defenderTable.setShowGrid(true);
        defenderTable.setGridColor(Color.BLACK);
        defenderTable.setIntercellSpacing(new Dimension(1, 1));
        defenderTable.setRowSelectionAllowed(false);
        defenderTable.setCellSelectionEnabled(false);
        defenderTable.setFocusable(false);
        defenderTable.getTableHeader().setReorderingAllowed(false);
        while (defenderTable.getColumnCount() > 1) {
            defenderTable.removeColumn(defenderTable.getColumnModel().getColumn(1));
        }
        TableColumn defenderColumn = defenderTable.getColumnModel().getColumn(0);
        defenderColumn.setPreferredWidth(140);
        defenderColumn.setMinWidth(140);
        defenderColumn.setCellRenderer(new TypeNameCellRenderer(false));
        defenderColumn.setHeaderRenderer(new CornerHeaderRenderer());
        defenderTable.setPreferredScrollableViewportSize(new Dimension(141, 0));

        // Main table shows only the effectiveness columns.
        effectivenessTable.removeColumn(effectivenessTable.getColumnModel().getColumn(0));
        for (int i = 0; i < effectivenessTable.getColumnCount(); i++) {
            TableColumn column = effectivenessTable.getColumnModel().getColumn(i);
            column.setPreferredWidth(110);
            column.setMinWidth(110);
            column.setHeaderRenderer(new TypeNameCellRenderer(true));
        }

        // Create a scroll pane with the table - header will be fixed automatically
        JScrollPane scrollPane = new JScrollPane(effectivenessTable);
        scrollPane.setRowHeaderView(defenderTable);
        scrollPane.setCorner(JScrollPane.UPPER_LEFT_CORNER, defenderTable.getTableHeader());

        // Ensure header height matches row height for proper alignment
        effectivenessTable.getTableHeader().setPreferredSize(
                new Dimension(effectivenessTable.getTableHeader().getPreferredSize().width, 60));
        defenderTable.getTableHeader().setPreferredSize(new Dimension(141, 60));

        // Increase scroll speed for mouse wheel
        scrollPane.getVerticalScrollBar().setUnitIncrement(20);
        scrollPane.getHorizontalScrollBar().setUnitIncrement(20);

    JLabel legendLabel = new JLabel(bundle.getString("GUI.teManualEditorDialog.legend"));
        legendLabel.setBorder(BorderFactory.createEmptyBorder(10, 10, 0, 10));

    JLabel iconHintLabel = new JLabel(
        getBundleString("GUI.teManualEditorDialog.iconHint",
            "Click type icon to view and edit interactions for that type."));
    iconHintLabel.setBorder(BorderFactory.createEmptyBorder(0, 10, 10, 10));

        JPanel tableCard = new JPanel(new BorderLayout(10, 10));
        tableCard.add(legendLabel, BorderLayout.NORTH);
    tableCard.add(scrollPane, BorderLayout.CENTER);
    tableCard.add(iconHintLabel, BorderLayout.SOUTH);

        viewLayout = new CardLayout();
        viewPanel = new JPanel(viewLayout);
        viewPanel.add(tableCard, "TABLE");
        viewPanel.add(createDetailCard(), "DETAIL");

        add(viewPanel, BorderLayout.CENTER);

        JPanel buttonsPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton resetButton = new JButton(bundle.getString("GUI.teManualEditorDialog.reset"));
        JButton cancelButton = new JButton(bundle.getString("GUI.teManualEditorDialog.cancel"));
        JButton applyButton = new JButton(bundle.getString("GUI.teManualEditorDialog.apply"));
        buttonsPanel.add(resetButton);
        buttonsPanel.add(cancelButton);
        buttonsPanel.add(applyButton);
        add(buttonsPanel, BorderLayout.SOUTH);

        resetButton.addActionListener(e -> {
            resetWorkingTable();
            tableModel.fireTableDataChanged();
            if (currentDetailType != null) {
                populateDetailGrid(attackGrid, currentDetailType, true);
                populateDetailGrid(defenseGrid, currentDetailType, false);
            }
        });
        cancelButton.addActionListener(e -> dispose());
        applyButton.addActionListener(e -> {
            resultTable = new TypeTable(workingTable);
            saved = true;
            dispose();
        });
        getRootPane().setDefaultButton(applyButton);

        defenderTable.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent event) {
                int row = defenderTable.rowAtPoint(event.getPoint());
                if (row >= 0) {
                    showTypeDetails(types.get(row));
                }
            }
        });

        effectivenessTable.getTableHeader().addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent event) {
                int column = effectivenessTable.columnAtPoint(event.getPoint());
                if (column >= 0) {
                    // View column 0 is model column 1 (the defender column moved
                    // to the frozen row header), so convert before indexing.
                    int modelColumn = effectivenessTable.convertColumnIndexToModel(column);
                    if (modelColumn >= 1) {
                        showTypeDetails(types.get(modelColumn - 1));
                    }
                }
            }
        });
    }

    private JPanel createDetailCard() {
        JPanel detailCard = new JPanel(new BorderLayout(10, 10));

        JPanel headerPanel = new JPanel();
        headerPanel.setLayout(new BoxLayout(headerPanel, BoxLayout.Y_AXIS));
        headerPanel.setBorder(new EmptyBorder(20, 20, 10, 20));

        detailTypeIconLabel = new JLabel();
        detailTypeIconLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        detailTypeIconLabel.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                viewLayout.show(viewPanel, "TABLE");
            }
        });

        detailTypeNameLabel = new JLabel();
        detailTypeNameLabel.setHorizontalAlignment(SwingConstants.CENTER);
        detailTypeNameLabel.setFont(detailTypeNameLabel.getFont().deriveFont(Font.BOLD, 24f));

        JPanel iconWrapper = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        iconWrapper.setOpaque(false);
        iconWrapper.add(detailTypeIconLabel);

        JPanel nameWrapper = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        nameWrapper.setOpaque(false);
        nameWrapper.add(detailTypeNameLabel);

        headerPanel.add(iconWrapper);
        headerPanel.add(Box.createVerticalStrut(8));
        headerPanel.add(nameWrapper);

        detailCard.add(headerPanel, BorderLayout.NORTH);

        JPanel contentPanel = new JPanel(new GridLayout(1, 2, 20, 0));
        contentPanel.setBorder(new EmptyBorder(0, 20, 20, 20));
        contentPanel.add(createDetailSection("GUI.teManualEditorDialog.detail.attack", "Attack", true));
        contentPanel.add(createDetailSection("GUI.teManualEditorDialog.detail.defense", "Defense", false));

        JScrollPane contentScrollPane = new JScrollPane(contentPanel);
        contentScrollPane.getVerticalScrollBar().setUnitIncrement(20);
        detailCard.add(contentScrollPane, BorderLayout.CENTER);

        JButton backButton = new JButton(getBundleString("GUI.teManualEditorDialog.back", "Back to Type Chart"));
        backButton.addActionListener(e -> viewLayout.show(viewPanel, "TABLE"));
        JPanel backPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        backPanel.setBorder(new EmptyBorder(0, 20, 20, 20));
        backPanel.add(backButton);
        detailCard.add(backPanel, BorderLayout.SOUTH);

        return detailCard;
    }

    private JPanel createDetailSection(String titleKey, String fallbackTitle, boolean attackSection) {
        JPanel section = new JPanel(new BorderLayout(5, 5));
        JLabel titleLabel = new JLabel(getBundleString(titleKey, fallbackTitle), SwingConstants.CENTER);
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 18f));
        section.add(titleLabel, BorderLayout.NORTH);

    JPanel grid = new JPanel(new GridLayout(0, 3, 12, 12));
        grid.setBorder(new EmptyBorder(10, 10, 10, 10));
        section.add(grid, BorderLayout.CENTER);

        if (attackSection) {
            attackGrid = grid;
        } else {
            defenseGrid = grid;
        }
        return section;
    }

    private void showTypeDetails(com.dabomstew.pkromio.gamedata.Type type) {
        ImageIcon icon = typeIcons.get(type);
        if (icon != null) {
            Image scaledImage = icon.getImage().getScaledInstance(96, 96, Image.SCALE_SMOOTH);
            detailTypeIconLabel.setIcon(new ImageIcon(scaledImage));
        } else {
            detailTypeIconLabel.setIcon(null);
        }

        detailTypeNameLabel.setText(type.name());

        currentDetailType = type;

        populateDetailGrid(attackGrid, type, true);
        populateDetailGrid(defenseGrid, type, false);

        viewLayout.show(viewPanel, "DETAIL");
    }

    private void populateDetailGrid(JPanel grid, com.dabomstew.pkromio.gamedata.Type focusType, boolean attack) {
        grid.removeAll();
        for (com.dabomstew.pkromio.gamedata.Type otherType : types) {
            Effectiveness effectiveness = attack ? workingTable.getEffectiveness(focusType, otherType)
                    : workingTable.getEffectiveness(otherType, focusType);
            grid.add(createTypeTile(focusType, otherType, attack, effectiveness));
        }
        grid.revalidate();
        grid.repaint();
    }

    private JPanel createTypeTile(com.dabomstew.pkromio.gamedata.Type focusType,
            com.dabomstew.pkromio.gamedata.Type otherType, boolean attack, Effectiveness effectiveness) {
        JPanel tile = new JPanel();
        tile.setLayout(new BoxLayout(tile, BoxLayout.Y_AXIS));
        tile.setBorder(new EmptyBorder(10, 10, 10, 10));
        Color baseColor = typeColor(otherType, false);
        tile.setBackground(mixWithWhite(baseColor, 0.15f));
        tile.setOpaque(true);

        JLabel iconLabel = new JLabel();
        iconLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        ImageIcon icon = typeIcons.get(otherType);
        if (icon != null) {
            Image scaledImage = icon.getImage().getScaledInstance(48, 48, Image.SCALE_SMOOTH);
            iconLabel.setIcon(new ImageIcon(scaledImage));
            iconLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            iconLabel.addMouseListener(new java.awt.event.MouseAdapter() {
                @Override
                public void mouseClicked(java.awt.event.MouseEvent e) {
                    showTypeDetails(otherType);
                }
            });
        }

        JLabel nameLabel = new JLabel(otherType.name(), SwingConstants.CENTER);
        nameLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

        JLabel multiplierLabel = new JLabel(effectMultiplier(effectiveness), SwingConstants.CENTER);
        multiplierLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

        nameLabel.setForeground(Color.BLACK);
        multiplierLabel.setForeground(Color.BLACK);

        JPanel iconWrap = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        iconWrap.setOpaque(false);
        iconWrap.add(iconLabel);

        JPanel nameWrap = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        nameWrap.setOpaque(false);
        nameWrap.add(nameLabel);

        JPanel multiplierWrap = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        multiplierWrap.setOpaque(false);
        multiplierWrap.add(multiplierLabel);

        tile.add(iconWrap);
        tile.add(Box.createVerticalStrut(6));
        tile.add(nameWrap);
        tile.add(Box.createVerticalStrut(4));
        tile.add(multiplierWrap);

        tile.setToolTipText(effectDescription(effectiveness));

        tile.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                editEffectiveness(focusType, otherType, attack);
            }
        });

        return tile;
    }

    private String effectMultiplier(Effectiveness effectiveness) {
        if (effectiveness == null) {
            return "";
        }
        switch (effectiveness) {
            case ZERO:
                return "0x";
            case HALF:
                return "0.5x";
            case DOUBLE:
                return "2x";
            default:
                return "1x";
        }
    }

    private void editEffectiveness(com.dabomstew.pkromio.gamedata.Type focusType,
            com.dabomstew.pkromio.gamedata.Type otherType, boolean attack) {
        Effectiveness current = attack ? workingTable.getEffectiveness(focusType, otherType)
                : workingTable.getEffectiveness(otherType, focusType);

        JComboBox<Effectiveness> combo = new JComboBox<>(allowedEffectivenesses);
        combo.setRenderer(new EffectivenessComboRenderer());
        combo.setSelectedItem(current);

        String titleKey = attack ? "GUI.teManualEditorDialog.detail.editAttack"
                : "GUI.teManualEditorDialog.detail.editDefense";
        String message = getBundleString(titleKey,
                attack ? "When %s attacks %s" : "When %s is attacked by %s");
        message = String.format(message, focusType.name(), otherType.name());

        int result = JOptionPane.showConfirmDialog(this, combo, message, JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE);
        if (result == JOptionPane.OK_OPTION && combo.getSelectedItem() instanceof Effectiveness) {
            Effectiveness selected = (Effectiveness) combo.getSelectedItem();
            if (attack) {
                workingTable.setEffectiveness(focusType, otherType, selected);
            } else {
                workingTable.setEffectiveness(otherType, focusType, selected);
            }
            tableModel.fireTableDataChanged();
            populateDetailGrid(attackGrid, focusType, true);
            populateDetailGrid(defenseGrid, focusType, false);
            effectivenessTable.revalidate();
            effectivenessTable.repaint();
        }
    }

    private String getBundleString(String key, String fallback) {
        try {
            return bundle.getString(key);
        } catch (Exception e) {
            return fallback;
        }
    }

    private void resetWorkingTable() {
        for (com.dabomstew.pkromio.gamedata.Type attacker : types) {
            for (com.dabomstew.pkromio.gamedata.Type defender : types) {
                workingTable.setEffectiveness(attacker, defender,
                        originalTable.getEffectiveness(attacker, defender));
            }
        }
    }

    private String effectDisplay(Effectiveness effectiveness) {
        if (effectiveness == null) {
            return "";
        }
        switch (effectiveness) {
            case ZERO:
                return bundle.getString("GUI.teManualEditorDialog.effect.zero");
            case HALF:
                return bundle.getString("GUI.teManualEditorDialog.effect.half");
            case NEUTRAL:
                return bundle.getString("GUI.teManualEditorDialog.effect.neutral");
            case DOUBLE:
                return bundle.getString("GUI.teManualEditorDialog.effect.double");
            default:
                return effectiveness.name();
        }
    }

    private String effectDescription(Effectiveness effectiveness) {
        if (effectiveness == null) {
            return "";
        }
        switch (effectiveness) {
            case ZERO:
                return bundle.getString("GUI.teManualEditorDialog.effect.description.zero");
            case HALF:
                return bundle.getString("GUI.teManualEditorDialog.effect.description.half");
            case NEUTRAL:
                return bundle.getString("GUI.teManualEditorDialog.effect.description.neutral");
            case DOUBLE:
                return bundle.getString("GUI.teManualEditorDialog.effect.description.double");
            default:
                return effectiveness.name();
        }
    }

    private Color effectColor(Effectiveness effectiveness) {
        if (effectiveness == null) {
            return Color.WHITE;
        }
        switch (effectiveness) {
            case ZERO:
                return new Color(224, 224, 224);
            case HALF:
                return new Color(255, 246, 207);
            case DOUBLE:
                return new Color(210, 235, 208);
            default:
                return Color.WHITE;
        }
    }

    private Color typeColor(com.dabomstew.pkromio.gamedata.Type type, boolean header) {
        Color base;
        switch (type) {
            case BUG:
                base = new Color(139, 175, 63); // Yellow-green from icon
                break;
            case DARK:
                base = new Color(95, 85, 85); // Dark brown-gray (slightly lighter)
                break;
            case DRAGON:
                base = new Color(100, 115, 240); // Closer to #5160E1 but lighter
                break;
            case ELECTRIC:
                base = new Color(247, 208, 44); // Yellow from icon
                break;
            case FAIRY:
                base = new Color(250, 135, 250); // Lighter version of #EE70EE profile icon color
                break;
            case FIGHTING:
                base = new Color(252, 142, 52); // Tiny tiny bit lighter vibrant orange
                break;
            case FIRE:
                base = new Color(230, 59, 59); // Red from icon
                break;
            case FLYING:
                base = new Color(139, 178, 215); // Light blue from icon
                break;
            case GHOST:
                base = new Color(125, 75, 125); // Purple (slightly lighter)
                break;
            case GRASS:
                base = new Color(85, 155, 65); // Green from icon (darker to match)
                break;
            case GROUND:
                base = new Color(145, 104, 63); // Brown from icon
                break;
            case ICE:
                base = new Color(103, 213, 238); // Cyan from icon
                break;
            case NORMAL:
                base = new Color(180, 185, 185); // Lighter, brighter gray from icon
                break;
            case POISON:
                base = new Color(145, 85, 175); // Purple from icon (darker)
                break;
            case PSYCHIC:
                base = new Color(238, 84, 141); // Pink from icon
                break;
            case ROCK:
                base = new Color(175, 168, 138); // Tan from icon (slightly lighter)
                break;
            case STEEL:
                base = new Color(115, 175, 195); // Teal from icon (lighter)
                break;
            case WATER:
                base = new Color(60, 120, 200); // Blue from icon (slightly darker)
                break;
            default:
                base = new Color(200, 200, 200);
                break;
        }
        return base; // Don't lighten - use the same color for both header and rows
    }

    private Color mixWithWhite(Color base, float ratio) {
        ratio = Math.max(0f, Math.min(1f, ratio));
        int r = (int) (base.getRed() + (255 - base.getRed()) * ratio);
        int g = (int) (base.getGreen() + (255 - base.getGreen()) * ratio);
        int b = (int) (base.getBlue() + (255 - base.getBlue()) * ratio);
        return new Color(r, g, b);
    }

    private final class TypeEffectivenessTableModel extends AbstractTableModel {
        @Override
        public int getRowCount() {
            return types.size();
        }

        @Override
        public int getColumnCount() {
            return types.size() + 1;
        }

        @Override
        public String getColumnName(int column) {
            if (column == 0) {
                return "";
            }
            return types.get(column - 1).name();
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            return columnIndex == 0 ? String.class : Effectiveness.class;
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return columnIndex > 0;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            com.dabomstew.pkromio.gamedata.Type defender = types.get(rowIndex);
            if (columnIndex == 0) {
                return defender.name();
            }
            com.dabomstew.pkromio.gamedata.Type attacker = types.get(columnIndex - 1);
            return workingTable.getEffectiveness(attacker, defender);
        }

        @Override
        public void setValueAt(Object aValue, int rowIndex, int columnIndex) {
            if (!(aValue instanceof Effectiveness) || columnIndex == 0) {
                return;
            }
            com.dabomstew.pkromio.gamedata.Type defender = types.get(rowIndex);
            com.dabomstew.pkromio.gamedata.Type attacker = types.get(columnIndex - 1);
            workingTable.setEffectiveness(attacker, defender, (Effectiveness) aValue);
            fireTableCellUpdated(rowIndex, columnIndex);
        }
    }

    private final class CornerHeaderRenderer extends JPanel implements TableCellRenderer {
        private final JLabel attackingLabel;
        private final JLabel defendingLabel;

        CornerHeaderRenderer() {
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setOpaque(true);
            setBorder(BorderFactory.createMatteBorder(0, 0, 1, 1, Color.BLACK));

            attackingLabel = new JLabel(getBundleString("GUI.teManualEditorDialog.corner.attack", "Attacking Type →"));
            attackingLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
            attackingLabel.setFont(attackingLabel.getFont().deriveFont(Font.BOLD, 14f));

            defendingLabel = new JLabel(getBundleString("GUI.teManualEditorDialog.corner.defense", "Defending Type ↓"));
            defendingLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

            add(Box.createVerticalStrut(6));
            add(attackingLabel);
            add(Box.createVerticalStrut(6));
            add(defendingLabel);
            add(Box.createVerticalStrut(6));
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                boolean hasFocus, int row, int column) {
            attackingLabel.setText(getBundleString("GUI.teManualEditorDialog.corner.attack", "Attacking Type →"));
            defendingLabel.setText(getBundleString("GUI.teManualEditorDialog.corner.defense", "Defending Type ↓"));
            Color headerBackground = table.getTableHeader().getBackground();
            setBackground(headerBackground);
            attackingLabel.setForeground(Color.BLACK);
            defendingLabel.setForeground(Color.BLACK);
            return this;
        }
    }

    private final class TypeNameCellRenderer extends JPanel implements TableCellRenderer {
        private final boolean header;
        private final JLabel iconLabel;
        private final JLabel textLabel;

        TypeNameCellRenderer(boolean header) {
            this.header = header;
            setLayout(null); // Absolute positioning for icon alignment
            setOpaque(true);

            iconLabel = new JLabel();
            iconLabel.setHorizontalAlignment(SwingConstants.CENTER);
            iconLabel.setVerticalAlignment(SwingConstants.CENTER);

            textLabel = new JLabel();
            textLabel.setHorizontalAlignment(header ? SwingConstants.CENTER : SwingConstants.LEFT);
            textLabel.setVerticalAlignment(SwingConstants.CENTER);

            add(iconLabel);
            add(textLabel);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                boolean hasFocus, int row, int column) {
            String text = value == null ? "" : value.toString();
            com.dabomstew.pkromio.gamedata.Type parsedType = parseType(text);

            if (parsedType != null) {
                // Set the icon if available
                ImageIcon icon = typeIcons.get(parsedType);
                if (icon != null) {
                    // Scale the icon to fit nicely
                    int iconSize = 48;
                    Image scaledImage = icon.getImage().getScaledInstance(iconSize, iconSize, Image.SCALE_SMOOTH);
                    iconLabel.setIcon(new ImageIcon(scaledImage));
                } else {
                    iconLabel.setIcon(null);
                }

                // Headers show only icons, rows show icon + text
                if (header) {
                    textLabel.setText("");
                    textLabel.setVisible(false);
                    iconLabel.setVisible(true);
                } else {
                    textLabel.setText(text);
                    textLabel.setVisible(true);
                    iconLabel.setVisible(true);
                }

                // Set background color - use same color for both header and rows
                Color background = typeColor(parsedType, false);
                setBackground(background);
                iconLabel.setOpaque(false);
                textLabel.setOpaque(false);

                // Always use white text for consistency
                textLabel.setForeground(Color.WHITE);
            } else {
                iconLabel.setIcon(null);
                textLabel.setText(text);
                setBackground(Color.WHITE);
                textLabel.setForeground(Color.BLACK);
            }

            // Handle borders for proper grid alignment
            if (header) {
                // Header cells should have borders to align with grid
                setBorder(BorderFactory.createMatteBorder(0, 0, 1, 1, Color.BLACK));
            } else {
                // Remove border - table grid handles it for body cells
                setBorder(null);
            }

            return this;
        }

        @Override
        public void doLayout() {
            super.doLayout();
            int width = getWidth();
            int height = getHeight();

            if (header) {
                // Center icon only
                iconLabel.setBounds(0, 0, width, height);
            } else {
                // Icon on left at fixed position, text follows
                int iconSize = 48;
                int iconX = 5; // Fixed left position
                int iconY = (height - iconSize) / 2;
                iconLabel.setBounds(iconX, iconY, iconSize, iconSize);

                // Text starts after icon
                int textX = iconX + iconSize + 5;
                textLabel.setBounds(textX, 0, width - textX, height);
            }
        }

        private com.dabomstew.pkromio.gamedata.Type parseType(String text) {
            if (text == null) {
                return null;
            }
            try {
                return com.dabomstew.pkromio.gamedata.Type.valueOf(text);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    private final class EffectivenessCellRenderer extends DefaultTableCellRenderer {
        EffectivenessCellRenderer() {
            setHorizontalAlignment(SwingConstants.CENTER);
            setOpaque(true);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                boolean hasFocus, int row, int column) {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            Effectiveness effectiveness = value instanceof Effectiveness ? (Effectiveness) value : null;
            setText(effectDisplay(effectiveness));
            if (isSelected) {
                setBackground(table.getSelectionBackground());
                setForeground(table.getSelectionForeground());
            } else {
                setBackground(effectColor(effectiveness));
                setForeground(table.getForeground());
            }
            setToolTipText(effectDescription(effectiveness));
            // Remove border - table grid handles it
            setBorder(null);
            return this;
        }
    }

    private final class EffectivenessComboRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected,
                boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            Effectiveness effectiveness = value instanceof Effectiveness ? (Effectiveness) value : null;
            setText(effectDisplay(effectiveness));
            return this;
        }
    }
}
