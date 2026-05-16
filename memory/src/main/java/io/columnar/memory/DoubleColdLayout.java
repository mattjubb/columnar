package io.columnar.memory;

import io.columnar.core.Validity;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Packed on-disk/off-heap layout for HOT {@code double} payloads plus Arrow-style validity
 * bitmap words. MAGIC identifies the serialized form; versioning leaves room for future
 * encodings without breaking readers.
 *
 * <p>Layout — all native-endian:
 * <pre>
 *   MAGIC (int32) = 0x434F4C44 ("COLD")
 *   VERSION (int32) = 1
 *   ROW_COUNT (int32)
 *   VALID_WORDS (int32) — number of validity {@code long} words (= ceil(rows/64))
 *   RESERVED (int64) = 0
 *   DOUBLE_VALUES [row_count * JAVA_DOUBLE_UNALIGNED]
 *   VALIDITY_WORDS [valid_words * JAVA_LONG_UNALIGNED]
 * </pre>
 */
public final class DoubleColdLayout {

    /** Identifies serialized double+cold payloads from this runtime. */
    public static final int MAGIC = 0x434F4C44; // 'C','O','L','D'

    public static final int VERSION = 1;

    /** Fixed header size including reserved padding — must match serialized header above. */
    public static final long HEADER_BYTES = ValueLayout.JAVA_INT.byteSize() * 4 + ValueLayout.JAVA_LONG.byteSize();


    public static int validityWordCount(int rowCount) {
        return (rowCount + 63) >>> 6;
    }

    public static long totalBytes(int rowCount) {
        int vw = validityWordCount(rowCount);
        return HEADER_BYTES + (long) rowCount * ValueLayout.JAVA_DOUBLE.byteSize()
                + (long) vw * ValueLayout.JAVA_LONG.byteSize();
    }

    /** Write header + payload + validity into {@code target} (must be {@link #totalBytes} long). */
    public static void write(MemorySegment target, double[] values, int size, Validity validity) {
        if (size < 0 || size > values.length) {
            throw new IllegalArgumentException("size=" + size + " values.length=" + values.length);
        }
        int vw = validityWordCount(size);
        if (validity.size() != size) {
            throw new IllegalArgumentException("validity.size=" + validity.size() + " != size=" + size);
        }
        long expect = totalBytes(size);
        if (target.byteSize() < expect) {
            throw new IllegalArgumentException("segment too small: " + target.byteSize() + " < " + expect);
        }
        long base = 0;
        target.set(ValueLayout.JAVA_INT_UNALIGNED, base, MAGIC);
        base += ValueLayout.JAVA_INT.byteSize();
        target.set(ValueLayout.JAVA_INT_UNALIGNED, base, VERSION);
        base += ValueLayout.JAVA_INT.byteSize();
        target.set(ValueLayout.JAVA_INT_UNALIGNED, base, size);
        base += ValueLayout.JAVA_INT.byteSize();
        target.set(ValueLayout.JAVA_INT_UNALIGNED, base, vw);
        base += ValueLayout.JAVA_INT.byteSize();
        target.set(ValueLayout.JAVA_LONG_UNALIGNED, base, 0L);
        base += ValueLayout.JAVA_LONG.byteSize();

        for (int i = 0; i < size; i++) {
            target.set(ValueLayout.JAVA_DOUBLE_UNALIGNED, base + (long) i * ValueLayout.JAVA_DOUBLE.byteSize(), values[i]);
        }
        base += (long) size * ValueLayout.JAVA_DOUBLE.byteSize();

        long[] words = validity.words();
        for (int w = 0; w < vw; w++) {
            target.set(
                    ValueLayout.JAVA_LONG_UNALIGNED,
                    base + (long) w * ValueLayout.JAVA_LONG.byteSize(),
                    words[w]);
        }
    }

    /** Allocate a native segment and serialize into it. */
    public static MemorySegment allocateAndWrite(Arena arena, double[] values, int size, Validity validity) {
        MemorySegment seg = arena.allocate(totalBytes(size), 8);
        write(seg, values, size, validity);
        return seg;
    }

    public static void readHeader(MemorySegment src, Header out) {
        long base = 0;
        out.magic = src.get(ValueLayout.JAVA_INT_UNALIGNED, base);
        base += ValueLayout.JAVA_INT.byteSize();
        out.version = src.get(ValueLayout.JAVA_INT_UNALIGNED, base);
        base += ValueLayout.JAVA_INT.byteSize();
        out.rowCount = src.get(ValueLayout.JAVA_INT_UNALIGNED, base);
        base += ValueLayout.JAVA_INT.byteSize();
        out.validWords = src.get(ValueLayout.JAVA_INT_UNALIGNED, base);
        base += ValueLayout.JAVA_INT.byteSize();
        out.reserved = src.get(ValueLayout.JAVA_LONG_UNALIGNED, base);
    }

    public static final class Header {
        public int magic;
        public int version;
        public int rowCount;
        public int validWords;
        public long reserved;
    }

    public static void validateHeader(Header h) {
        if (h.magic != MAGIC) {
            throw new IllegalArgumentException("bad magic: 0x" + Integer.toHexString(h.magic));
        }
        if (h.version != VERSION) {
            throw new IllegalArgumentException("unsupported version: " + h.version);
        }
        if (h.rowCount < 0) {
            throw new IllegalArgumentException("negative rowCount: " + h.rowCount);
        }
        int expectVw = validityWordCount(h.rowCount);
        if (h.validWords != expectVw) {
            throw new IllegalArgumentException(
                    "validWords=" + h.validWords + " expected " + expectVw + " for rowCount=" + h.rowCount);
        }
    }
}
