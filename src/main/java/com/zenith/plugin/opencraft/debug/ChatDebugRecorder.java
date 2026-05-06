package com.zenith.plugin.opencraft.debug;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public final class ChatDebugRecorder {

    private static final int MAX_EVENTS = 200;
    private final Deque<DebugEvent> events = new ArrayDeque<>(MAX_EVENTS);

    public synchronized void record(final String stage, final String detail) {
        if (events.size() >= MAX_EVENTS) {
            events.removeFirst();
        }
        events.addLast(new DebugEvent(Instant.now().toString(), compact(stage), compact(detail)));
    }

    public synchronized List<DebugEvent> recent(final int limit) {
        final List<DebugEvent> snapshot = new ArrayList<>(Math.min(limit, events.size()));
        int skipped = Math.max(0, events.size() - limit);
        int index = 0;
        for (final DebugEvent event : events) {
            if (index++ < skipped) {
                continue;
            }
            snapshot.add(event);
        }
        return snapshot;
    }

    public synchronized void clear() {
        events.clear();
    }

    private static String compact(@Nullable final String text) {
        if (text == null) {
            return "";
        }
        final String singleLine = text.replaceAll("[\\r\\n]+", " ").strip();
        return singleLine.length() <= 220 ? singleLine : singleLine.substring(0, 217) + "...";
    }

    public record DebugEvent(String timestamp, String stage, String detail) {
    }
}
