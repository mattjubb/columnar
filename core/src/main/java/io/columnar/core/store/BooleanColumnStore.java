package io.columnar.core.store;

import io.columnar.core.Column;
import io.columnar.core.ColumnChunk;
import io.columnar.core.DataType;
import io.columnar.core.Validity;
import io.columnar.core.ValidityBuilder;
import io.columnar.core.chunk.BooleanChunk;
import io.columnar.core.chunk.HotBooleanChunk;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;

import java.util.Arrays;
import java.util.List;

/** Bit-packed boolean column store; uses one {@code long} per 64 active rows. */
public final class BooleanColumnStore extends ColumnStore {

    private final ObjectArrayList<BooleanChunk> sealed = new ObjectArrayList<>();
    private long[] activeWords;
    private int activeSize;
    private ValidityBuilder activeValidity;

    public BooleanColumnStore(String name, int chunkCapacity) {
        super(name, DataType.BOOLEAN, chunkCapacity);
        this.activeWords = new long[Math.max((chunkCapacity + 63) >>> 6, 1)];
        this.activeValidity = new ValidityBuilder(chunkCapacity);
    }

    public BooleanColumnStore(String name) {
        this(name, ColumnChunk.DEFAULT_CAPACITY);
    }

    public void appendBoolean(boolean value) {
        if (activeSize >= chunkCapacity) sealActive();
        if (value) {
            activeWords[activeSize >>> 6] |= 1L << (activeSize & 63);
        }
        activeSize++;
        activeValidity.appendValid();
    }

    @Override
    public void appendNull() {
        if (activeSize >= chunkCapacity) sealActive();
        activeSize++;
        activeValidity.appendNull();
    }

    @Override
    public void append(Object value) {
        if (value == null) appendNull();
        else if (value instanceof Boolean b) appendBoolean(b);
        else throw new IllegalArgumentException(
                "cannot append " + value.getClass().getName() + " to BOOLEAN column " + name);
    }

    @Override
    public void sealActive() {
        if (activeSize == 0) return;
        int wc = (activeSize + 63) >>> 6;
        long[] copy = Arrays.copyOf(activeWords, wc);
        sealed.add(new HotBooleanChunk(copy, activeSize, activeValidity.toValidity()));
        Arrays.fill(activeWords, 0L);
        activeSize = 0;
        activeValidity = new ValidityBuilder(chunkCapacity);
    }

    @Override
    public long size() {
        long total = 0;
        for (BooleanChunk c : sealed) total += c.size();
        return total + activeSize;
    }

    @Override public int sealedChunkCount() { return sealed.size(); }
    @Override public int activeSize() { return activeSize; }
    @Override public List<BooleanChunk> sealedChunks() { return sealed; }

    @Override
    public Column snapshot() {
        ObjectArrayList<ColumnChunk> all = new ObjectArrayList<>(sealed.size() + 1);
        all.addAll(sealed);
        if (activeSize > 0) {
            int wc = (activeSize + 63) >>> 6;
            long[] copy = Arrays.copyOf(activeWords, wc);
            Validity vSnap = activeValidity.toValidity();
            all.add(new HotBooleanChunk(copy, activeSize, vSnap));
        }
        return Column.of(name, DataType.BOOLEAN, all);
    }
}
