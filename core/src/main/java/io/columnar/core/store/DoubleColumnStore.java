package io.columnar.core.store;

import io.columnar.core.Column;
import io.columnar.core.ColumnChunk;
import io.columnar.core.DataType;
import io.columnar.core.Validity;
import io.columnar.core.ValidityBuilder;
import io.columnar.core.chunk.DoubleChunk;
import io.columnar.core.chunk.HotDoubleChunk;
import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;

import java.util.List;

public final class DoubleColumnStore extends ColumnStore {

    private final ObjectArrayList<DoubleChunk> sealed = new ObjectArrayList<>();
    private DoubleArrayList activeValues;
    private ValidityBuilder activeValidity;

    public DoubleColumnStore(String name, int chunkCapacity) {
        super(name, DataType.DOUBLE, chunkCapacity);
        this.activeValues = new DoubleArrayList(chunkCapacity);
        this.activeValidity = new ValidityBuilder(chunkCapacity);
    }

    public DoubleColumnStore(String name) {
        this(name, ColumnChunk.DEFAULT_CAPACITY);
    }

    public void appendDouble(double value) {
        if (activeValues.size() >= chunkCapacity) sealActive();
        activeValues.add(value);
        activeValidity.appendValid();
    }

    @Override
    public void appendNull() {
        if (activeValues.size() >= chunkCapacity) sealActive();
        activeValues.add(0.0);
        activeValidity.appendNull();
    }

    @Override
    public void append(Object value) {
        if (value == null) appendNull();
        else if (value instanceof Number n) appendDouble(n.doubleValue());
        else throw new IllegalArgumentException(
                "cannot append " + value.getClass().getName() + " to DOUBLE column " + name);
    }

    @Override
    public void sealActive() {
        if (activeValues.isEmpty()) return;
        double[] vals = activeValues.toDoubleArray();
        sealed.add(new HotDoubleChunk(vals, vals.length, activeValidity.toValidity()));
        activeValues = new DoubleArrayList(chunkCapacity);
        activeValidity = new ValidityBuilder(chunkCapacity);
    }

    @Override
    public long size() {
        long total = 0;
        for (DoubleChunk c : sealed) total += c.size();
        return total + activeValues.size();
    }

    @Override public int sealedChunkCount() { return sealed.size(); }
    @Override public int activeSize() { return activeValues.size(); }
    @Override public List<DoubleChunk> sealedChunks() { return sealed; }

    @Override
    public Column snapshot() {
        ObjectArrayList<ColumnChunk> all = new ObjectArrayList<>(sealed.size() + 1);
        all.addAll(sealed);
        if (!activeValues.isEmpty()) {
            double[] copy = activeValues.toDoubleArray();
            Validity vSnap = activeValidity.toValidity();
            all.add(new HotDoubleChunk(copy, copy.length, vSnap));
        }
        return Column.of(name, DataType.DOUBLE, all);
    }
}
