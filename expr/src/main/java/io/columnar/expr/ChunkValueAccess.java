package io.columnar.expr;

import io.columnar.core.Column;
import io.columnar.core.ColumnChunk;
import io.columnar.core.chunk.BooleanChunk;
import io.columnar.core.chunk.DoubleChunk;
import io.columnar.core.chunk.IntChunk;
import io.columnar.core.chunk.LongChunk;

/** Row-major navigation helpers shared by interpreter + specialized kernels. */
final class ChunkValueAccess {

    private ChunkValueAccess() {}

    static boolean isNull(Column col, long globalRow) {
        long pos = 0;
        for (ColumnChunk chunk : col.chunks()) {
            if (globalRow < pos + chunk.size()) {
                int local = (int) (globalRow - pos);
                return chunk.validity().isNull(local);
            }
            pos += chunk.size();
        }
        throw new IndexOutOfBoundsException("globalRow=" + globalRow + " column=" + col.name());
    }

    static double readDoubleNonNull(Column col, long globalRow) {
        int local = localIndex(col, globalRow);
        DoubleChunk dc = chunkAt(col, globalRow, DoubleChunk.class);
        return dc.getDouble(local);
    }

    static long readLongNonNull(Column col, long globalRow) {
        int local = localIndex(col, globalRow);
        LongChunk lc = chunkAt(col, globalRow, LongChunk.class);
        return lc.getLong(local);
    }

    static int readIntNonNull(Column col, long globalRow) {
        int local = localIndex(col, globalRow);
        IntChunk ic = chunkAt(col, globalRow, IntChunk.class);
        return ic.getInt(local);
    }

    static boolean readBooleanNonNull(Column col, long globalRow) {
        int local = localIndex(col, globalRow);
        BooleanChunk bc = chunkAt(col, globalRow, BooleanChunk.class);
        return bc.getBoolean(local);
    }

    private static int localIndex(Column col, long globalRow) {
        long pos = 0;
        for (ColumnChunk chunk : col.chunks()) {
            if (globalRow < pos + chunk.size()) {
                return (int) (globalRow - pos);
            }
            pos += chunk.size();
        }
        throw new IndexOutOfBoundsException("globalRow=" + globalRow + " column=" + col.name());
    }

    private static <T extends ColumnChunk> T chunkAt(Column col, long globalRow, Class<T> type) {
        long pos = 0;
        for (ColumnChunk chunk : col.chunks()) {
            if (globalRow < pos + chunk.size()) {
                if (!type.isInstance(chunk)) {
                    throw new UnsupportedOperationException(
                            "unexpected chunk impl " + chunk.getClass().getSimpleName());
                }
                return type.cast(chunk);
            }
            pos += chunk.size();
        }
        throw new IndexOutOfBoundsException("globalRow=" + globalRow);
    }
}
