package io.columnar.memory;

import io.columnar.core.Residency;
import io.columnar.core.Validity;
import io.columnar.core.chunk.DoubleChunk;
import io.columnar.core.chunk.HotDoubleChunk;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Off-heap {@link DoubleChunk} backed by a native {@link MemorySegment} in the
 * {@link DoubleColdLayout} wire format. Values and validity travel together so
 * eviction can drop the on-heap arrays without losing nullability information.
 */
public final class WarmDoubleChunk implements DoubleChunk {

    private final MemorySegment segment;
    private final int size;
    private final Validity validity;

    public WarmDoubleChunk(MemorySegment segment) {
        this.segment = segment;
        DoubleColdLayout.Header h = new DoubleColdLayout.Header();
        DoubleColdLayout.readHeader(segment, h);
        DoubleColdLayout.validateHeader(h);
        this.size = h.rowCount;
        long valuesBase = DoubleColdLayout.HEADER_BYTES;
        long validBase = valuesBase + (long) size * ValueLayout.JAVA_DOUBLE.byteSize();
        int vw = DoubleColdLayout.validityWordCount(size);
        long[] words = new long[vw];
        for (int w = 0; w < vw; w++) {
            words[w] = segment.get(
                    ValueLayout.JAVA_LONG_UNALIGNED,
                    validBase + (long) w * ValueLayout.JAVA_LONG.byteSize());
        }
        this.validity = Validity.fromWords(words, size);
    }

    /** Copy a HOT chunk into a freshly allocated native segment. */
    public static WarmDoubleChunk fromHot(MemorySegment packedSegment) {
        return new WarmDoubleChunk(packedSegment);
    }

    /** Promote to on-heap {@link HotDoubleChunk} (copies storage). */
    public HotDoubleChunk promoteToHot() {
        double[] values = new double[size];
        long valuesBase = DoubleColdLayout.HEADER_BYTES;
        for (int i = 0; i < size; i++) {
            values[i] = segment.get(
                    ValueLayout.JAVA_DOUBLE_UNALIGNED,
                    valuesBase + (long) i * ValueLayout.JAVA_DOUBLE.byteSize());
        }
        return new HotDoubleChunk(values, size, validity);
    }

    public MemorySegment segment() {
        return segment;
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public Residency residency() {
        return Residency.WARM;
    }

    @Override
    public Validity validity() {
        return validity;
    }

    @Override
    public double getDouble(int row) {
        if (row < 0 || row >= size) {
            throw new IndexOutOfBoundsException("row=" + row + " size=" + size);
        }
        long valuesBase = DoubleColdLayout.HEADER_BYTES;
        return segment.get(
                ValueLayout.JAVA_DOUBLE_UNALIGNED,
                valuesBase + (long) row * ValueLayout.JAVA_DOUBLE.byteSize());
    }
}
