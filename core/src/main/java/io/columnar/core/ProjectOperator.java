package io.columnar.core;

import io.columnar.api.Column;
import io.columnar.api.ColumnarSlice;
import io.columnar.api.Schema;
import io.columnar.api.Slicing;
import io.columnar.api.Table;
import io.columnar.api.Viewport;

import java.util.LinkedHashSet;
import java.util.List;

/**
 * Pure column-projection / rename operator. No expressions yet — that's the job
 * of the {@code :expr} module's {@code VectorProjector} once it lands.
 *
 * <p>Pushes the projected column subset upstream so source operators can
 * elide loading unrequested columns.
 */
public final class ProjectOperator implements Operator {

    private final Operator upstream;
    private final List<String> outputColumns;
    private final Schema outputSchema;

    public ProjectOperator(Operator upstream, List<String> outputColumns) {
        this.upstream = upstream;
        this.outputColumns = List.copyOf(outputColumns);
        this.outputSchema = upstream.outputSchema().select(this.outputColumns);
    }

    @Override
    public Schema outputSchema() {
        return outputSchema;
    }

    @Override
    public List<Table> upstreams() {
        return upstream.upstreams();
    }

    @Override
    public String signature() {
        return "Project[" + String.join(",", outputColumns) + "](" + upstream.signature() + ")";
    }

    @Override
    public ColumnarSlice compute(Viewport viewport, PullContext ctx) {
        // Push column subset upstream — only request what we'll keep.
        Viewport upViewport = Viewport.builder()
                .rows(viewport.rows())
                .columns(new LinkedHashSet<>(outputColumns))
                .build();
        ColumnarSlice in = upstream.compute(upViewport, ctx);

        // Project (and reorder) to the requested columns.
        List<Column> picked = Slicing.project(in.columns(), outputColumns);
        long rowCount = picked.isEmpty() ? 0L : picked.get(0).size();
        if (viewport.hasLimit()) {
            long limit = viewport.limit().getAsLong();
            if (rowCount > limit) {
                picked = Slicing.slice(picked, 0, limit);
                rowCount = limit;
            }
        }
        return new ColumnarSlice(outputSchema, picked, rowCount, in.version());
    }
}
