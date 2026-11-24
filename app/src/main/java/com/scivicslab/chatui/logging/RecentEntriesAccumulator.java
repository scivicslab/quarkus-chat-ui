package com.scivicslab.chatui.logging;

import com.scivicslab.pojoactor.core.accumulator.Accumulator;

import java.util.List;

/**
 * An {@link Accumulator} that keeps only the most recent {@code capacity} entries, discarding
 * older ones. Used as a {@code MultiplexerAccumulator} target so a REST endpoint can serve
 * "recent log lines" without the buffer growing without bound ({@code
 * com.scivicslab.pojoactor.core.accumulator.BufferedAccumulator} keeps every entry forever).
 * The entries belong to one owner, an actor ({@link RecentRing}): {@link #add} is a {@code tell} and
 * {@link #recent()} an {@code ask} answered after the entries told before it, so any thread may do either.
 */
public class RecentEntriesAccumulator implements Accumulator {

    /** One accumulated entry, as passed to {@link #add}. */
    public record Entry(long time, String source, String type, String data) {}

    private final RecentRing<Entry> entries;

    public RecentEntriesAccumulator(int capacity) {
        this.entries = new RecentRing<>("recent-entries", capacity);
    }

    @Override
    public void add(String source, String type, String data) {
        entries.add(new Entry(System.currentTimeMillis(), source, type, data));
    }

    /** @return the buffered entries, oldest first */
    public List<Entry> recent() {
        return entries.recent();
    }

    @Override
    public String getSummary() {
        return "RecentEntriesAccumulator: " + entries.count() + "/" + entries.capacity() + " entries";
    }

    @Override
    public int getCount() {
        return entries.count();
    }

    @Override
    public void clear() {
        entries.clear();
    }

    /** Stops the owner of the entries, when the buffer's conversation or job is gone. */
    public void close() {
        entries.close();
    }
}
