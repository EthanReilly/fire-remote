package com.fireremote.app;

import android.content.Context;
import android.util.TypedValue;

final class Theme {
    static final int BG = 0xFF0D1015;
    static final int SURFACE = 0xFF1A1F27;
    static final int SURFACE_PRESSED = 0xFF2A313D;
    static final int SURFACE_HIGH = 0xFF222833;
    static final int TEXT = 0xFFE9EDF2;
    static final int MUTED = 0xFF8C96A5;
    static final int ACCENT = 0xFFFF9F2E;
    static final int ACCENT_PRESSED = 0xFFE0861A;
    static final int ON_ACCENT = 0xFF1B1206;
    static final int OK_GREEN = 0xFF3DD68C;
    static final int WARN = 0xFFFFC145;
    static final int ERROR = 0xFFFF6B6B;

    private Theme() {
    }

    static float dp(Context c, float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics());
    }

    static int dpi(Context c, float v) {
        return Math.round(dp(c, v));
    }

    static float sp(Context c, float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, c.getResources().getDisplayMetrics());
    }
}
