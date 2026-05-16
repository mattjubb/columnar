package io.columnar.core.store;

import io.columnar.core.Column;
import io.columnar.core.ColumnChunk;
import io.columnar.core.DataType;
import io.columnar.core.Validity;
import io.columnar.core.ValidityBuilder;
import io.columnar.core.chunk.HotIntChunk;
import io.columnar.core.chunk.IntChunk;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;

import java.util.List;

public final class IntColumnStore extends ColumnStore {

    private final ObjectArrayList<IntChunk> sealed = new ObjectArrayList<>();
    private IntArrayList activeValues;
    private ValidityBuilder activeValidity;

    public IntColumnStore(String name, int chunkCapacity) {
        super(name, DataType.INT, chunkCapacity);
        this.activeValues = new IntArrayList(chunkCapacity);
        this.activeValidity = new ValidityBuilder(chunkCapacity);
    }

    public IntColumnStore(String name) {
        this(name, ColumnChunk.DEFAULT_CAPACITY);
    }

    public void appendInt(int value) {
        if (activeValues.size() >= chunkCapacity) sealActive();
        activeValues.add(value);
        activeValidity.appendValid();
    }

    @Override
    public void appendNull() {
        if (activeValues.size() >= chunkCapacity) sealActive();
        activeValues.add(0);
        activeValidity.appendNull();
    }

    @Override
    public void append(Object value) {
        if (value == null) appendNull();
        else if (value instanceof Number n) appendInt(n.intValue());
        else throw new IllegalArgumentException(
                "cannot append " + value.getClass().getName() + " to INT column " + name);
    }

    @Override
    public void sealActive() {
        if (activeValues.isEmpty()) return;
        int[] vals = activeValues.toIntArray();
        sealed.add(new HotIntChunk(vals, vals.length, activeValidity.toValidity()));
        activeValues = new IntArrayList(chunkCapacity);
        activeValidity = new ValidityBuilder(chunkCapacity);
    }

    @Override
    public long size() {
        long total = 0;
        for (IntChunk c : sealed) total += c.size();
        return total + activeValues.size();
    }

    @Override public int sealedChunkCount() { return sealed.size(); }
    @Override public int activeSize() { return activeValues.size(); }
    @Override public List<IntChunk> sealedChunks() { return sealed; }

    @Override
    public Column snapshot() {
        ObjectArrayList<ColumnChunk> all = new ObjectArrayList<>(sealed.size() + 1);
        all.addAll(sealed);
        if (!activeValues.isEmpty()) {
            int[] copy = activeValues.toIntArray();
            Validity vSnap = activeValidity.toValidity();
            all.add(new HotIntChunk(copy, copy.length, vSnap));
        }
        return Column.of(name, DataType.INT, all);
    }
}
