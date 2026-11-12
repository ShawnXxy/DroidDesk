package com.termux.x11;

public final class X11RenderGeometry {
    public static final int WIDTH_ALIGNMENT_PX = 8;
    public static final int COMPAT_1366X768_ALIGNED_WIDTH = 1360;
    public static final int COMPAT_1366X768_HEIGHT = 768;
    public static final int COMPAT_1366X768_WIDTH = 1366;

    private X11RenderGeometry() {}

    public static int alignedWidth(int width, int height) {
        if (width <= 0 || height <= 0)
            throw new IllegalArgumentException("Render dimensions must be positive");
        int aligned = Math.max(WIDTH_ALIGNMENT_PX, width - width % WIDTH_ALIGNMENT_PX);
        // libxcvt preserves this common mode despite its horizontal granularity.
        return aligned == COMPAT_1366X768_ALIGNED_WIDTH && height == COMPAT_1366X768_HEIGHT
                ? COMPAT_1366X768_WIDTH
                : aligned;
    }
}
