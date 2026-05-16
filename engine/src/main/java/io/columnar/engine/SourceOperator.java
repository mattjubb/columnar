package io.columnar.engine;

import io.columnar.core.ColumnarSlice;
import io.columnar.core.Schema;
import io.columnar.core.Table;
import io.columnar.core.Viewport;

import java.util.List;

/**
 * Adapter that lifts a {@link Table} (typically a static or live source) into
 * the operator world.
 */
public final class SourceOperator implements Operator {

    private final Table table;

    public SourceOperator(Table table) {
        this.table = table;
    }

    public Table table() {
        return table;
    }

    @Override
    public Schema outputSchema() {
        return table.schema();
    }

    @Override
    public List<Table> upstreams() {
        return List.of(table);
    }

    @Override
    public String signature() {
        return "Source(" + System.identityHashCode(table) + ")";
    }

    @Override
    public ColumnarSlice compute(Viewport viewport, PullContext ctx) {
        return ctx.read(table, viewport);
    }
}
