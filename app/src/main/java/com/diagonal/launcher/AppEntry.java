package com.diagonal.launcher;

import android.content.ComponentName;
import android.graphics.Bitmap;

/** One launchable app shown as a diagonal line. */
final class AppEntry {
    final ComponentName component;
    final String label;
    final Bitmap icon;
    int color;
    int textColor;
    /** Auto colour picked from the icon (color may be overridden by the user). */
    int autoColor;
    /** Icon is a one-colour glyph (two-tone icon): it is tinted with textColor and its colour can be changed. */
    boolean glyph;
    final String key;
    /** The smart centre stripe (clock / weather / notification / music), not an app. */
    final boolean info;

    private static final android.graphics.ColorFilter INK_WHITE =
            new android.graphics.PorterDuffColorFilter(0xFFFFFFFF, android.graphics.PorterDuff.Mode.SRC_IN);
    private static final android.graphics.ColorFilter INK_DARK =
            new android.graphics.PorterDuffColorFilter(0xFF111111, android.graphics.PorterDuff.Mode.SRC_IN);

    /** Colour filter for drawing the icon (glyphs follow the text colour). */
    android.graphics.ColorFilter iconFilter() {
        if (!glyph) return null;
        return textColor == 0xFFFFFFFF ? INK_WHITE : INK_DARK;
    }

    // Text-measurement cache used by DiagonalView (UI thread only).
    float labelWidth = -1f;
    CharSequence ellLabel;
    float ellWidth;
    int ellBucket = -1;

    AppEntry(ComponentName component, String label, Bitmap icon, int color, int textColor) {
        this.component = component;
        this.label = label;
        this.icon = icon;
        this.color = color;
        this.textColor = textColor;
        this.autoColor = color;
        this.key = component.flattenToString();
        this.info = false;
    }

    AppEntry(ComponentName placeholder) {
        this.component = placeholder;
        this.label = "";
        this.icon = null;
        this.color = 0xFF16161C;
        this.textColor = 0xFFFFFFFF;
        this.key = "info";
        this.info = true;
    }
}
