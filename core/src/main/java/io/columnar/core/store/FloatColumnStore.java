package io.columnar.core.store;

import io.columnar.core.Column;
import io.columnar.core.ColumnChunk;
import io.columnar.core.DataType;
import io.columnar.core.Validity;
import io.columnar.core.ValidityBuilder;
import io.columnar.core.chunk.FloatChunk;
import io.columnar.core.chunk.HotFloatChunk;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;

import java.util.List;

public final class FloatColumnStore extends ColumnStore {

    private final ObjectArrayList<FloatChunk> sealed = new ObjectArrayList<>();
    private FloatArrayList activeValues;
    private ValidityBuilder activeValidity;

    public FloatColumnStore(String name, int chunkCapacity) {
        super(name, DataType.FLOAT, chunkCapacity);
        this.activeValues = new FloatArrayList(chunkCapacity);
        this.activeValidity = new ValidityBuilder(chunkCapacity);
    }

    public FloatColumnStore(String name) {
        this(name, ColumnChunk.DEFAULT_CAPACITY);
    }

    public void appendFloat(float value) {
        if (activeValues.size() >= chunkCapacity) sealActive();
        activeValues.add(value);
        activeValidity.appendValid();
    }

    @Override
    public void appendNull() {
        if (activeValues.size() >= chunkCapacity) sealActive();
        activeValues.add(0f);
        activeValidity.appendNull();
    }

    @Override
    public void append(Object value) {
        if (value == null) appendNull();
        else if (value instanceof Number n) appendFloat(n.floatValue());
        else throw new IllegalArgumentException(
                "cannot append " + value.getClass().getName() + " to FLOAT column " + name);
    }

    @Override
    public void sealActive() {
        if (activeValues.isEmpty()) return;
        float[] vals = activeValues.toFloatArray();
        sealed.add(new HotFloatChunk(vals, vals.length, activeValidity.toValidity()));
        activeValues = new FloatArrayList(chunkCapacity);
        activeValidity = new ValidityBuilder(chunkCapacity);
    }

    @Override
    public long size() {
        long total = 0;
        for (FloatChunk c : sealed) total += c.size();
        return total + activeValues.size();
    }

    @Override public int sealedChunkCount() { return sealed.size(); }
    @Override public int activeSize() { return activeValues.size(); }
    @Override public List<FloatChunk> sealedChunks() { return sealed; }

    @Override
    public Column snapshot() {
        ObjectArrayList<ColumnChunk> all = new ObjectArrayList<>(sealed.size() + 1);
        all.addAll(sealed);
        if (!activeValues.isEmpty()) {
            float[] copy = activeValues.toFloatArray();
            Validity vSnap = activeValidity.toValidity();
            all.add(new HotFloatChunk(copy, copy.length, vSnap));
        }
        return Column.of(name, DataType.FLOAT, all);
    }
}
