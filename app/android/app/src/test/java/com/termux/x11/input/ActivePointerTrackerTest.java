package com.termux.x11.input;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class ActivePointerTrackerTest {
    @Test
    public void updateAcceptsSparsePointerIds() {
        ActivePointerTracker tracker = new ActivePointerTracker();

        assertEquals(setOf(), tracker.update(setOf(0, 12, 31)));
    }

    @Test
    public void updateReturnsAbsentPointerExactlyOnce() {
        ActivePointerTracker tracker = new ActivePointerTracker();
        tracker.update(setOf(0, 12, 31));

        assertEquals(setOf(12), tracker.update(setOf(0, 31)));
        assertEquals(setOf(), tracker.update(setOf(0, 31)));
    }

    @Test
    public void beginAndEndPreserveRawPointerIds() {
        ActivePointerTracker tracker = new ActivePointerTracker();
        tracker.begin(0);
        tracker.begin(12);
        tracker.begin(31);

        tracker.end(12);

        assertEquals(setOf(0, 31), tracker.update(setOf()));
    }

    @Test
    public void clearReturnsRemainingPointersExactlyOnce() {
        ActivePointerTracker tracker = new ActivePointerTracker();
        tracker.begin(0);
        tracker.begin(12);
        tracker.begin(31);
        tracker.end(12);

        assertEquals(setOf(0, 31), tracker.clear());
        assertEquals(setOf(), tracker.clear());
    }

    private static Set<Integer> setOf(Integer... pointerIds) {
        return new HashSet<>(Arrays.asList(pointerIds));
    }
}
