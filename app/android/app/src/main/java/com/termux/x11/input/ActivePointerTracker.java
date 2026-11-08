package com.termux.x11.input;

import java.util.HashSet;
import java.util.Set;

final class ActivePointerTracker {
    private final Set<Integer> activePointerIds = new HashSet<>();

    Set<Integer> update(Set<Integer> observedPointerIds) {
        Set<Integer> endedPointerIds = new HashSet<>(activePointerIds);
        endedPointerIds.removeAll(observedPointerIds);
        activePointerIds.clear();
        activePointerIds.addAll(observedPointerIds);
        return endedPointerIds;
    }

    void begin(int pointerId) {
        activePointerIds.add(pointerId);
    }

    void end(int pointerId) {
        activePointerIds.remove(pointerId);
    }

    Set<Integer> clear() {
        Set<Integer> endedPointerIds = new HashSet<>(activePointerIds);
        activePointerIds.clear();
        return endedPointerIds;
    }
}
