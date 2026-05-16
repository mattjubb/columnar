package io.columnar.core.store;

import io.columnar.core.Column;
import io.columnar.core.ColumnChunk;
import io.columnar.core.DataType;
import io.columnar.core.Validity;
import io.columnar.core.ValidityBuilder;
import io.columnar.core.chunk.HotLongChunk;
import io.columnar.core.chunk.LongChunk;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;

import java.util.List;

public final class LongColumnStore extends ColumnStore {

    private final ObjectArrayList<LongChunk> sealed = new ObjectArrayList<>();
    private LongArrayList activeValues;
    private ValidityBuilder activeValidity;

    public LongColumnStore(String name, int chunkCapacity) {
        super(name, DataType.LONG, chunkCapacity);
        this.activeValues = new LongArrayList(chunkCapacity);
        this.activeValidity = new ValidityBuilder(chunkCapacity);
    }

    public LongColumnStore(String name) {
        this(name, ColumnChunk.DEFAULT_CAPACITY);
    }

    public void appendLong(long value) {
        if (activeValues.size() >= chunkCapacity) {
            sealActive();
        }
        activeValues.add(value);
        activeValidity.appendValid();
    }

    @Override
    public void appendNull() {
        if (activeValues.size() >= chunkCapacity) {
            sealActive();
        }
        activeValues.add(0L);
        activeValidity.appendNull();
    }

    @Override
    public void append(Object value) {
        if (value == null) {
            appendNull();
        } else if (value instanceof Number n) {
            appendLong(n.longValue());
        } else {
            throw new IllegalArgumentException(
                    "cannot append " + value.getClass().getName() + " to LONG column " + name);
        }
    }

    @Override
    public void sealActive() {
        if (activeValues.isEmpty()) {
            return;
        }
        long[] vals = activeValues.toLongArray();
        sealed.add(new HotLongChunk(vals, vals.length, activeValidity.toValidity()));
        activeValues = new LongArrayList(chunkCapacity);
        activeValidity = new ValidityBuilder(chunkCapacity);
    }

    @Override
    public long size() {
        long total = 0;
        for (LongChunk c : sealed) total += c.size();
        return total + activeValues.size();
    }

    @Override
    public int sealedChunkCount() {
        return sealed.size();
    }

    @Override
    public int activeSize() {
        return activeValues.size();
    }

    @Override
    public List<LongChunk> sealedChunks() {
        return sealed;
    }

    @Override
    public Column snapshot() {
        ObjectArrayList<ColumnChunk> all = new ObjectArrayList<>(sealed.size() + 1);
        all.addAll(sealed);
        if (!activeValues.isEmpty()) {
            // Snapshot the active prefix by copying values + validity.
            long[] copy = activeValues.toLongArray();
            Validity vSnap = activeValidity.toValidity();
            all.add(new HotLongChunk(copy, copy.length, vSnap));
        }
        return Column.of(name, DataType.LONG, all);
    }
}
