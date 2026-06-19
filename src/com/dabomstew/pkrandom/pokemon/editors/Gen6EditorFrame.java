package com.dabomstew.pkrandom.pokemon.editors;

import com.dabomstew.pkrandom.log.ManualEditRegistry;
import com.dabomstew.pkromio.romhandlers.RomHandler;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.List;
import java.util.Map;

/**
 * Main editor frame for Generation 6 Pokemon games (X, Y, Omega Ruby, Alpha Sapphire).
 * Provides tabbed editors for Personal Data, TMs, Learnsets, Evolutions, Egg Moves, and Moves.
 * Follows the same structure as Gen5EditorFrame.
 */
public class Gen6EditorFrame extends JFrame {

    private final RomHandler romHandler;
    private JTabbedPane tabbedPane;

    // Editor panels
    private PokemonCardViewPanel cardViewPanel;
    private MoveCardViewPanel moveCardViewPanel;
    private TrainerEditorPanel trainerEditorPanel;
    private Gen6PersonalSheetPanel personalSheetPanel;
    private Gen6TMsSheetPanel tmsSheetPanel;
    private Gen6LearnsetsSheetPanel learnsetsSheetPanel;
    private Gen6EvolutionsSheetPanel evolutionsSheetPanel;
    private Gen6MovesSheetPanel movesSheetPanel;
    private Gen6MoveTutorsSheetPanel moveTutorsSheetPanel;
    private Gen6EggMovesSheetPanel eggMovesSheetPanel;
    // Wild-encounter editor (generation-agnostic). Gated: stays null when the game has no encounter data.
    private EncountersEditorPanel encountersEditorPanel;
    // Static-encounter editor (generation-agnostic). Gated: stays null when the game can't change static Pokemon.
    private StaticEncountersEditorPanel staticEncountersEditorPanel;
    // Starters editor (generation-agnostic). Gated: stays null when the game exposes no starters.
    private StartersEditorPanel startersEditorPanel;
    // In-game trades editor (generation-agnostic). Gated: stays null when the game exposes no trades.
    private InGameTradesEditorPanel inGameTradesEditorPanel;
    // Field-item editor (generation-agnostic). Gated: stays null when the game exposes no field items.
    private FieldItemsEditorPanel fieldItemsEditorPanel;
    // Pickup-items editor (generation-agnostic). Gated: stays null when the game exposes no pickup data.
    private PickupItemsEditorPanel pickupItemsEditorPanel;
    // Item editor (generation-agnostic). Gated: stays null when the game exposes no items.
    private ItemsEditorPanel itemsEditorPanel;

    public Gen6EditorFrame(RomHandler romHandler) {
        this.romHandler = romHandler;
        initializeUI();
    }

    private void initializeUI() {
        setTitle("Gen 6 Pokemon Editor - " + romHandler.getROMName());
        setSize(1200, 800);
        setLocationRelativeTo(null);
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);

        // Add window listener to restore unsaved changes when window closes
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                // Restore any unsaved changes in all panels
                personalSheetPanel.onWindowClosing();
                trainerEditorPanel.onWindowClosing();
                tmsSheetPanel.onWindowClosing();
                learnsetsSheetPanel.onWindowClosing();
                eggMovesSheetPanel.onWindowClosing();
                evolutionsSheetPanel.onWindowClosing();
                movesSheetPanel.onWindowClosing();
                moveTutorsSheetPanel.onWindowClosing();
                if (encountersEditorPanel != null) {
                    encountersEditorPanel.onWindowClosing();
                }
                if (staticEncountersEditorPanel != null) {
                    staticEncountersEditorPanel.onWindowClosing();
                }
                if (startersEditorPanel != null) {
                    startersEditorPanel.onWindowClosing();
                }
                if (inGameTradesEditorPanel != null) {
                    inGameTradesEditorPanel.onWindowClosing();
                }
                if (fieldItemsEditorPanel != null) {
                    fieldItemsEditorPanel.onWindowClosing();
                }
                if (pickupItemsEditorPanel != null) {
                    pickupItemsEditorPanel.onWindowClosing();
                }
                if (itemsEditorPanel != null) {
                    itemsEditorPanel.onWindowClosing();
                }
            }
        });

        // Create tabbed pane with better styling
        tabbedPane = new JTabbedPane();
        tabbedPane.setFont(new Font("Segoe UI", Font.BOLD, 12));
        if (!com.dabomstew.pkrandom.gui.ThemeManager.isDarkModeApplied()) {
            tabbedPane.setBackground(Color.WHITE);
            tabbedPane.setForeground(Color.BLACK); // Black text for readability
        }

        // Create Card View panel
        cardViewPanel = new PokemonCardViewPanel(romHandler);
        cardViewPanel.setSaveAction(this::saveAll);
        tabbedPane.addTab("Card View", cardViewPanel);
        moveCardViewPanel = new MoveCardViewPanel(romHandler);
        moveCardViewPanel.setSaveAction(this::saveAll);
        tabbedPane.addTab("Move Card", moveCardViewPanel);

        // Create Personal Sheet panel
        personalSheetPanel = new Gen6PersonalSheetPanel(romHandler);
        tabbedPane.addTab("Personal Sheet", personalSheetPanel);

        // Create TMs Sheet panel
        tmsSheetPanel = new Gen6TMsSheetPanel(romHandler);
        tabbedPane.addTab("TMs Sheet", tmsSheetPanel);

        // Create Learnsets Sheet panel
        learnsetsSheetPanel = new Gen6LearnsetsSheetPanel(romHandler);
        tabbedPane.addTab("Learnsets Sheet", learnsetsSheetPanel);

        // Create Egg Moves panel
        eggMovesSheetPanel = new Gen6EggMovesSheetPanel(romHandler);
        tabbedPane.addTab("Egg Moves", eggMovesSheetPanel);

        // Create Evolutions Sheet panel
        evolutionsSheetPanel = new Gen6EvolutionsSheetPanel(romHandler);
        tabbedPane.addTab("Evolutions Sheet", evolutionsSheetPanel);

        // Create Moves Sheet panel
        movesSheetPanel = new Gen6MovesSheetPanel(romHandler);
        tabbedPane.addTab("Moves Sheet", movesSheetPanel);

        // Create Move Tutors panel
        moveTutorsSheetPanel = new Gen6MoveTutorsSheetPanel(romHandler);
        tabbedPane.addTab("Move Tutors", moveTutorsSheetPanel);

        // Wild Encounters (only when the game actually has encounter data).
        if (hasEncounterData()) {
            encountersEditorPanel = new EncountersEditorPanel(romHandler);
            encountersEditorPanel.setSaveAction(this::saveAll);
            tabbedPane.addTab("Wild Encounters", encountersEditorPanel);
        }

        // Static Encounters (only when the game supports changing static Pokemon).
        if (hasStaticData()) {
            staticEncountersEditorPanel = new StaticEncountersEditorPanel(romHandler);
            staticEncountersEditorPanel.setSaveAction(this::saveAll);
            tabbedPane.addTab("Static Encounters", staticEncountersEditorPanel);
        }

        // Starters (only when the game actually exposes starter Pokemon).
        if (hasStarterData()) {
            startersEditorPanel = new StartersEditorPanel(romHandler);
            startersEditorPanel.setSaveAction(this::saveAll);
            tabbedPane.addTab("Starters", startersEditorPanel);
        }

        // In-Game Trades (only when the game actually exposes trade data).
        if (hasInGameTradeData()) {
            inGameTradesEditorPanel = new InGameTradesEditorPanel(romHandler);
            inGameTradesEditorPanel.setSaveAction(this::saveAll);
            tabbedPane.addTab("In-Game Trades", inGameTradesEditorPanel);
        }

        // Field Items (only when the game actually exposes field-item data).
        if (hasFieldItemData()) {
            fieldItemsEditorPanel = new FieldItemsEditorPanel(romHandler);
            fieldItemsEditorPanel.setSaveAction(this::saveAll);
            tabbedPane.addTab("Field Items", fieldItemsEditorPanel);
        }

        // Pickup Items (only when the game actually exposes pickup data).
        if (hasPickupItemData()) {
            pickupItemsEditorPanel = new PickupItemsEditorPanel(romHandler);
            pickupItemsEditorPanel.setSaveAction(this::saveAll);
            tabbedPane.addTab("Pickup Items", pickupItemsEditorPanel);
        }

        // Items (only when the game actually exposes items). Read-only ID/Name/TM?;
        // editable Allowed/Bad flags that steer the randomizer's item pools.
        if (hasItemData()) {
            itemsEditorPanel = new ItemsEditorPanel(romHandler);
            itemsEditorPanel.setSaveAction(this::saveAll);
            tabbedPane.addTab("Items", itemsEditorPanel);
        }

        trainerEditorPanel = new TrainerEditorPanel(romHandler);
        trainerEditorPanel.setSaveAction(this::saveAll);
        tabbedPane.addTab("Trainers", trainerEditorPanel);

        add(tabbedPane, BorderLayout.CENTER);

        // Add menu bar
        createMenuBar();
    }

    private void createMenuBar() {
        JMenuBar menuBar = new JMenuBar();

        JMenu fileMenu = new JMenu("File");
        JMenuItem saveAllItem = new JMenuItem("Save All");
        saveAllItem.addActionListener(e -> saveAll());
        JMenuItem closeItem = new JMenuItem("Close");
        closeItem.addActionListener(e -> dispatchEvent(new WindowEvent(this, WindowEvent.WINDOW_CLOSING)));

        fileMenu.add(saveAllItem);
        fileMenu.addSeparator();
        fileMenu.add(closeItem);

        menuBar.add(fileMenu);
        setJMenuBar(menuBar);
    }

    // True when the loaded game exposes wild-encounter data, so the Wild Encounters tab is worth adding.
    private boolean hasEncounterData() {
        try {
            return !romHandler.getEncounters(true).isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    // True when the loaded game supports changing static Pokemon, so the Static Encounters tab is worth adding.
    private boolean hasStaticData() {
        try {
            return romHandler.canChangeStaticPokemon() && !romHandler.getStaticPokemon().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    // True when the loaded game exposes starter Pokemon, so the Starters tab is worth adding.
    private boolean hasStarterData() {
        try {
            return !romHandler.getStarters().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    // True when the loaded game exposes in-game trade data, so the In-Game Trades tab is worth adding.
    private boolean hasInGameTradeData() {
        try {
            return !romHandler.getInGameTrades().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    // True when the loaded game exposes field-item data, so the Field Items tab is worth adding.
    private boolean hasFieldItemData() {
        try {
            return !romHandler.getFieldItems().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    // True when the loaded game exposes pickup data, so the Pickup Items tab is worth adding.
    private boolean hasPickupItemData() {
        try {
            return !romHandler.getPickupItems().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    // True when the loaded game exposes items, so the Items tab is worth adding.
    private boolean hasItemData() {
        try {
            return !romHandler.getItems().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    private void saveAll() {
        // Silence each panel's own "saved!" popup; show one final confirmation instead.
        EditorUtils.suppressSaveDialogs = true;
        try {
            personalSheetPanel.save();
            tmsSheetPanel.save();
            learnsetsSheetPanel.save();
            eggMovesSheetPanel.save();
            evolutionsSheetPanel.save();
            movesSheetPanel.save();
            moveCardViewPanel.save();
            moveTutorsSheetPanel.save();
            cardViewPanel.save();
            trainerEditorPanel.save();
            if (encountersEditorPanel != null) {
                encountersEditorPanel.save();
            }
            if (staticEncountersEditorPanel != null) {
                staticEncountersEditorPanel.save();
            }
            if (startersEditorPanel != null) {
                startersEditorPanel.save();
            }
            if (inGameTradesEditorPanel != null) {
                inGameTradesEditorPanel.save();
            }
            if (fieldItemsEditorPanel != null) {
                fieldItemsEditorPanel.save();
            }
            if (pickupItemsEditorPanel != null) {
                pickupItemsEditorPanel.save();
            }
            if (itemsEditorPanel != null) {
                itemsEditorPanel.save();
            }
        } finally {
            EditorUtils.suppressSaveDialogs = false;
        }

        Map<String, List<String>> manualSections = ManualEditRegistry.getInstance().snapshot();
        if (!manualSections.isEmpty()) {
            ManualEditLogDialog.show(this, manualSections, romHandler.getROMName());
        }

        JOptionPane.showMessageDialog(this,
                "All editor changes applied. They'll be written to the ROM file when you Save or Randomize in the main window.",
                "Save Complete",
                JOptionPane.INFORMATION_MESSAGE);
    }
}
