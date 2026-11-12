package com.termux.x11;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.junit.Test;

public class X11RenderGeometryTest {
    @Test
    public void alignsWidthsDownToEightPixelGranularityAndClampsMinimum() {
        assertEquals(1920, X11RenderGeometry.alignedWidth(1920, 1080));
        assertEquals(1912, X11RenderGeometry.alignedWidth(1919, 1080));
        assertEquals(1080, X11RenderGeometry.alignedWidth(1085, 2340));
        assertEquals(808, X11RenderGeometry.alignedWidth(810, 1792));
        assertEquals(8, X11RenderGeometry.alignedWidth(8, 600));
        assertEquals(8, X11RenderGeometry.alignedWidth(7, 600));
        assertEquals(8, X11RenderGeometry.alignedWidth(1, 600));
    }

    @Test
    public void expands1360AlignedWidthTo1366OnlyFor768HeightCompatibilityMode() {
        assertEquals(
                1366,
                X11RenderGeometry.alignedWidth(
                        X11RenderGeometry.COMPAT_1366X768_WIDTH,
                        X11RenderGeometry.COMPAT_1366X768_HEIGHT));
        assertEquals(1366, X11RenderGeometry.alignedWidth(1360, 768));
        assertEquals(1360, X11RenderGeometry.alignedWidth(1366, 767));
        assertEquals(1360, X11RenderGeometry.alignedWidth(1366, 769));
        assertEquals(1360, X11RenderGeometry.alignedWidth(1366, 900));
        assertEquals(1360, X11RenderGeometry.alignedWidth(1360, 900));
        assertEquals(1360, X11RenderGeometry.alignedWidth(1360, 1080));
        assertEquals(1352, X11RenderGeometry.alignedWidth(1359, 768));
        assertEquals(1368, X11RenderGeometry.alignedWidth(1368, 768));
    }

    @Test
    public void rejectsNonPositiveDimensions() {
        assertInvalidDimensions(0, 768);
        assertInvalidDimensions(-8, 768);
        assertInvalidDimensions(1366, 0);
        assertInvalidDimensions(1366, -1);
    }

    private static void assertInvalidDimensions(int width, int height) {
        try {
            X11RenderGeometry.alignedWidth(width, height);
            fail("Expected IllegalArgumentException for " + width + "x" + height);
        } catch (IllegalArgumentException expected) {
            // Expected invalid render dimensions to be rejected.
        }
    }
}
