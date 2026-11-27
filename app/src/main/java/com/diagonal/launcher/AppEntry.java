package com.diagonal.launcher;

import android.content.ComponentName;
import android.graphics.Bitmap;

/** One launchable app shown as a diagonal line. */
final class AppEntry {
    final ComponentName component;
    final String label;
    final Bitmap icon;
    final int color;
    final int textColor;
    final String key;

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
        this.key = component.flattenToString();
    }
}
