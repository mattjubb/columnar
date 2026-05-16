package io.columnar.expr;

import io.columnar.core.Column;

import java.util.List;
import java.util.Set;

/** Vectorized bitmask predicate emitted by interpreter or ByteBuddy. */
public interface CompiledPredicate {

    Set<String> requiredColumns();

    void evalChunk(List<Column> cols, int chunkIdx, int rowCount, long[] outBits);
}
