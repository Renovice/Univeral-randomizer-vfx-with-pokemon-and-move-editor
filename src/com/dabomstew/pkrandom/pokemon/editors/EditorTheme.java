package com.dabomstew.pkrandom.pokemon.editors;

import com.dabomstew.pkrandom.gui.ThemeManager;

import java.awt.Color;

/**
 * Semantic palette for the sheet editors.
 *
 * Getters resolve against {@link ThemeManager#isDarkModeApplied()} — the theme
 * actually installed for this session — so the whole UI (including paint-time
 * cell renderers) stays consistent; a menu toggle only takes effect on restart.
 *
 * Light values intentionally match the colors that used to be hardcoded
 * throughout the sheet panels, so light mode is unchanged. Dark values match
 * FlatLaf's own dark palette so the editors blend with the rest of the window.
 */
final class EditorTheme {

    private EditorTheme() {
    }

    static boolean isDark() {
        // Use the session's installed theme, not the live preference, so editor
        // cells don't repaint in a half-applied theme when the menu is toggled.
        return ThemeManager.isDarkModeApplied();
    }

    // Dark values match FlatLaf's own dark palette (Panel.background #3C3F41,
    // Table/TextField.background #46494B, foreground #DDDDDD, Component.borderColor
    // #616365, gridColor #5A5E60, selection #4B6EAF/#EEEEEE) so the editor tables
    // sit in the same colour family as the rest of the FlatLaf window.

    /** Table / cell / viewport background (light: white). */
    static Color surface() {
        return isDark() ? new Color(0x34, 0x37, 0x39) : Color.WHITE;
    }

    /** Toolbar / panel strip background (light: 250,250,250). */
    static Color toolbar() {
        return isDark() ? new Color(0x2A, 0x2D, 0x2F) : new Color(250, 250, 250);
    }

    /** Borders and separators (light: 200,200,200). */
    static Color border() {
        return isDark() ? new Color(0x61, 0x63, 0x65) : new Color(200, 200, 200);
    }

    /** Primary text on surface (light: black; dark: bright for high contrast). */
    static Color text() {
        return isDark() ? new Color(0xF0, 0xF0, 0xF0) : Color.BLACK;
    }

    /** Secondary/info text (light: 100,100,100). */
    static Color mutedText() {
        return isDark() ? new Color(0xC0, 0xC0, 0xC0) : new Color(100, 100, 100);
    }

    /** Slightly emphasized text (light: 60,60,60). */
    static Color strongText() {
        return isDark() ? Color.WHITE : new Color(60, 60, 60);
    }

    /** Alternating row stripe (light: 250,250,250). */
    static Color altRow() {
        return isDark() ? new Color(0x39, 0x3C, 0x3E) : new Color(250, 250, 250);
    }

    /** Frozen (ID/Name) column row background (light: 245,245,245). */
    static Color frozenRow() {
        return isDark() ? new Color(0x2E, 0x30, 0x32) : new Color(245, 245, 245);
    }

    /** Table header background (light: 245,245,245). */
    static Color headerBg() {
        return isDark() ? new Color(0x2A, 0x2D, 0x2F) : new Color(245, 245, 245);
    }

    /** Table selection background (light: the classic 184,207,229 blue). */
    static Color selectionBg() {
        return isDark() ? new Color(0x4B, 0x6E, 0xAF) : new Color(184, 207, 229);
    }

    /** Text on top of {@link #selectionBg()}. */
    static Color selectionFg() {
        return isDark() ? new Color(0xEE, 0xEE, 0xEE) : Color.BLACK;
    }

    /** Table grid lines (light: 224,224,224). */
    static Color grid() {
        return isDark() ? new Color(0x5A, 0x5E, 0x60) : new Color(224, 224, 224);
    }
}
