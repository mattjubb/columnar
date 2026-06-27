package io.columnar.api.store;

import io.columnar.api.Column;
import io.columnar.api.ColumnChunk;
import io.columnar.api.DataType;
import io.columnar.api.Validity;
import io.columnar.api.ValidityBuilder;
import io.columnar.api.chunk.HotInstantChunk;
import io.columnar.api.chunk.InstantChunk;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;

import java.time.Instant;
import java.util.List;

/** Stores timestamps as primitive {@code long} epoch nanos to avoid boxing. */
public final class InstantColumnStore extends ColumnStore {

    private final ObjectArrayList<InstantChunk> sealed = new ObjectArrayList<>();
    private LongArrayList activeNanos;
    private ValidityBuilder activeValidity;

    public InstantColumnStore(String name, int chunkCapacity) {
        super(name, DataType.INSTANT, chunkCapacity);
        this.activeNanos = new LongArrayList(chunkCapacity);
        this.activeValidity = new ValidityBuilder(chunkCapacity);
    }

    public InstantColumnStore(String name) {
        this(name, ColumnChunk.DEFAULT_CAPACITY);
    }

    public void appendEpochNano(long epochNano) {
        if (activeNanos.size() >= chunkCapacity) sealActive();
        activeNanos.add(epochNano);
        activeValidity.appendValid();
    }

    public void appendInstant(Instant instant) {
        if (instant == null) {
            appendNull();
        } else {
            long nanos = Math.multiplyExact(instant.getEpochSecond(), 1_000_000_000L) + instant.getNano();
            appendEpochNano(nanos);
        }
    }

    @Override
    public void appendNull() {
        if (activeNanos.size() >= chunkCapacity) sealActive();
        activeNanos.add(0L);
        activeValidity.appendNull();
    }

    @Override
    public void append(Object value) {
        if (value == null) appendNull();
        else if (value instanceof Instant i) appendInstant(i);
        else if (value instanceof Number n) appendEpochNano(n.longValue());
        else throw new IllegalArgumentException(
                "cannot append " + value.getClass().getName() + " to INSTANT column " + name);
    }

    @Override
    public void sealActive() {
        if (activeNanos.isEmpty()) return;
        long[] vals = activeNanos.toLongArray();
        sealed.add(new HotInstantChunk(vals, vals.length, activeValidity.toValidity()));
        activeNanos = new LongArrayList(chunkCapacity);
        activeValidity = new ValidityBuilder(chunkCapacity);
    }

    @Override
    public long size() {
        long total = 0;
        for (InstantChunk c : sealed) total += c.size();
        return total + activeNanos.size();
    }

    @Override public int sealedChunkCount() { return sealed.size(); }
    @Override public int activeSize() { return activeNanos.size(); }
    @Override public List<InstantChunk> sealedChunks() { return sealed; }

    @Override
    public Column snapshot() {
        ObjectArrayList<ColumnChunk> all = new ObjectArrayList<>(sealed.size() + 1);
        all.addAll(sealed);
        if (!activeNanos.isEmpty()) {
            long[] copy = activeNanos.toLongArray();
            Validity vSnap = activeValidity.toValidity();
            all.add(new HotInstantChunk(copy, copy.length, vSnap));
        }
        return Column.of(name, DataType.INSTANT, all);
    }
}
