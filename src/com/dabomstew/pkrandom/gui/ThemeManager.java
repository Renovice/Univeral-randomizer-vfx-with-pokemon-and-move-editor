package com.dabomstew.pkrandom.gui;

import com.formdev.flatlaf.FlatDarkLaf;

import javax.swing.*;
import java.util.prefs.Preferences;

/**
 * Global light/dark theme switcher.
 *
 * <p>Light mode keeps the platform (Windows) look and feel, exactly as before
 * &mdash; those controls are drawn by the OS, so they look perfectly native, but
 * for the same reason they cannot be recoloured dark from Java.
 *
 * <p>Dark mode therefore uses <b>FlatLaf</b> (FlatDarkLaf), a self-painting
 * cross-platform look and feel. Because FlatLaf draws every control itself in
 * pure Java, it can make the whole UI genuinely dark while still looking clean
 * and flat (the closest practical thing to "a normal app, but dark"). It also
 * handles fonts, HiDPI scaling and text antialiasing correctly on its own.
 *
 * <p>The choice is persisted via {@link Preferences} and applied on the next
 * launch (see {@link #applyTheme()} in {@code main()}). We deliberately do not
 * swap the look and feel live: Swing cannot reliably rebuild the native Windows
 * look at runtime, and the editor panels bake their colours in at construction
 * time, so a live swap leaves a half-old/half-new ("cooked") UI. Applying the
 * theme once at startup keeps both light and dark looking clean.
 *
 * <p>Editor panels with custom painting can consult {@link #isDarkMode()}.
 */
public final class ThemeManager {

    private static final Preferences PREFS = Preferences.userNodeForPackage(ThemeManager.class);
    private static final String PREF_KEY = "darkMode";

    /** The theme actually installed this session (snapshotted in applyTheme). */
    private static boolean appliedDark;

    private ThemeManager() {
    }

    /** The persisted preference — i.e. the theme that will apply on the next launch. */
    public static boolean isDarkMode() {
        return PREFS.getBoolean(PREF_KEY, false);
    }

    /**
     * Whether dark mode is actually active in THIS session (the look and feel is
     * installed once at startup and never swapped live). Rendering code must use
     * this, not {@link #isDarkMode()}: reading the live preference at paint time
     * would make an open editor repaint in a half-applied theme the moment the
     * user toggles the menu, even though the toggle only takes effect on restart.
     */
    public static boolean isDarkModeApplied() {
        return appliedDark;
    }

    /**
     * Persists the dark-mode preference (applied on next launch). Flushes
     * immediately so the choice survives even if the JVM is killed before the
     * background preferences sync runs.
     */
    public static void setDarkMode(boolean dark) {
        PREFS.putBoolean(PREF_KEY, dark);
        try {
            PREFS.flush();
        } catch (java.util.prefs.BackingStoreException ignored) {
            // If the registry can't be written the toggle just won't persist;
            // never let that take the app down.
        }
    }

    /**
     * Retained for call-site compatibility (invoked first thing in {@code main()}).
     * FlatLaf manages fonts and text antialiasing itself, and the Windows look and
     * feel uses the OS settings, so there is nothing to configure here anymore.
     */
    public static void initFontRendering() {
        // intentionally empty
    }

    /** Installs the look and feel matching the saved preference. Call on the EDT. */
    public static void applyTheme() {
        appliedDark = isDarkMode(); // snapshot the theme we install for this whole session
        try {
            if (isDarkMode()) {
                // Larger base font for readability — FlatLaf derives every component's font
                // from "defaultFont", so this enlarges the whole dark UI (set before setup()).
                UIManager.put("defaultFont", new javax.swing.plaf.FontUIResource("Segoe UI", java.awt.Font.PLAIN, 14));
                FlatDarkLaf.setup();
                applyDarkTextContrast();
                darkenDarkBackgrounds();
            } else {
                String lafName = UIManager.getSystemLookAndFeelClassName();
                // Same rule as before dark mode existed: native LaF only on Windows.
                if (lafName.equalsIgnoreCase("com.sun.java.swing.plaf.windows.WindowsLookAndFeel")) {
                    UIManager.setLookAndFeel(lafName);
                } else {
                    UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
                }
            }
        } catch (Exception e) {
            // A failed theme switch should never take the app down.
            e.printStackTrace();
        }
    }

    /**
     * Brightens FlatLaf's dark-mode text. FlatLaf's default foreground (#DDDDDD,
     * disabled #A6A6A6) reads as low-contrast for some users, so we push enabled
     * text close to white and disabled text up to a clearly-legible grey. Must run
     * after {@code FlatDarkLaf.setup()} so it overrides the freshly-installed values.
     */
    private static void applyDarkTextContrast() {
        java.awt.Color fg = new javax.swing.plaf.ColorUIResource(0xF0, 0xF0, 0xF0);
        java.awt.Color disabled = new javax.swing.plaf.ColorUIResource(0xC0, 0xC0, 0xC0);
        String[] fgKeys = {
                "Label.foreground", "Button.foreground", "CheckBox.foreground", "RadioButton.foreground",
                "ToggleButton.foreground", "ComboBox.foreground", "List.foreground", "Table.foreground",
                "TableHeader.foreground", "TabbedPane.foreground", "TitledBorder.titleColor",
                "TextField.foreground", "FormattedTextField.foreground", "PasswordField.foreground",
                "TextArea.foreground", "TextPane.foreground", "EditorPane.foreground", "Spinner.foreground",
                "Menu.foreground", "MenuItem.foreground", "CheckBoxMenuItem.foreground",
                "RadioButtonMenuItem.foreground", "MenuBar.foreground", "PopupMenu.foreground",
                "ToolTip.foreground", "Tree.foreground", "Panel.foreground", "Viewport.foreground",
                "ScrollPane.foreground", "OptionPane.messageForeground", "Slider.foreground",
                "textText", "controlText", "infoText", "menuText", "windowText"
        };
        for (String k : fgKeys) {
            UIManager.put(k, fg);
        }
        String[] disabledKeys = {
                "Label.disabledForeground", "Button.disabledText", "CheckBox.disabledText",
                "RadioButton.disabledText", "ToggleButton.disabledText", "ComboBox.disabledForeground",
                "Menu.disabledForeground", "MenuItem.disabledForeground", "CheckBoxMenuItem.disabledForeground",
                "RadioButtonMenuItem.disabledForeground", "TabbedPane.disabledForeground",
                "textInactiveText", "TextField.inactiveForeground", "FormattedTextField.inactiveForeground",
                "TextArea.inactiveForeground", "Spinner.disabledForeground"
        };
        for (String k : disabledKeys) {
            UIManager.put(k, disabled);
        }
    }

    /**
     * Deepens FlatLaf's dark backgrounds. FlatLaf's default panel grey (#3C3F41)
     * reads as too bright, so every background-family key is darkened by a uniform
     * step — which keeps the panel/component/button relationships intact while
     * leaving borders, grid lines, text and selection colours untouched.
     */
    private static void darkenDarkBackgrounds() {
        int d = 18;
        String[] bgKeys = {
                "control", "Panel.background", "ToolBar.background", "MenuBar.background",
                "Menu.background", "MenuItem.background", "CheckBoxMenuItem.background",
                "RadioButtonMenuItem.background", "PopupMenu.background", "OptionPane.background",
                "TabbedPane.background", "Viewport.background", "ScrollPane.background",
                "RootPane.background", "Separator.background", "TextField.background",
                "FormattedTextField.background", "PasswordField.background", "TextArea.background",
                "TextPane.background", "EditorPane.background", "ComboBox.background",
                "ComboBox.buttonBackground", "Spinner.background", "List.background", "Table.background",
                "Tree.background", "TableHeader.background", "Button.background", "ToggleButton.background",
                "ProgressBar.background", "Slider.background"
        };
        for (String k : bgKeys) {
            java.awt.Color c = UIManager.getColor(k);
            if (c != null) {
                UIManager.put(k, new javax.swing.plaf.ColorUIResource(
                        Math.max(0, c.getRed() - d), Math.max(0, c.getGreen() - d), Math.max(0, c.getBlue() - d)));
            }
        }
    }
}
