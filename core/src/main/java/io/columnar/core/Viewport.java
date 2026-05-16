package io.columnar.core;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

/**
 * The contract between a consumer and the engine. A {@code Viewport} declares
 * exactly which rows and columns the consumer wants. Operators upstream see this
 * propagated requirement and compute only what is needed to satisfy it.
 *
 * <p>{@link #ALL} requests every row and every column.
 */
public final class Viewport {

    public static final Viewport ALL = new Viewport(RowRange.ALL, null, null, OptionalLong.empty());

    private final RowRange rows;
    /** {@code null} means "every column from the upstream schema". */
    private final Set<String> columns;
    private final List<SortKey> order;
    private final OptionalLong limit;

    private Viewport(RowRange rows, Set<String> columns, List<SortKey> order, OptionalLong limit) {
        this.rows = rows;
        this.columns = columns;
        this.order = order;
        this.limit = limit;
    }

    public RowRange rows() {
        return rows;
    }

    /** {@link Optional#empty()} == "all columns from upstream". */
    public Optional<Set<String>> columns() {
        return Optional.ofNullable(columns);
    }

    public Optional<List<SortKey>> order() {
        return Optional.ofNullable(order);
    }

    public OptionalLong limit() {
        return limit;
    }

    public boolean hasLimit() {
        return limit.isPresent();
    }

    /**
     * Combine two viewports such that the result satisfies both. Used by the
     * cache to coalesce overlapping subscriber demands.
     */
    public Viewport union(Viewport other) {
        RowRange r = new RowRange(
                Math.min(this.rows.from(), other.rows.from()),
                Math.max(this.rows.to(), other.rows.to()));
        Set<String> cols;
        if (this.columns == null || other.columns == null) {
            cols = null;
        } else {
            cols = new LinkedHashSet<>(this.columns);
            cols.addAll(other.columns);
        }
        // Order and limit are not unionable in a meaningful way; drop them.
        return new Viewport(r, cols, null, OptionalLong.empty());
    }

    public static Builder builder() {
        return new Builder();
    }

    public static Viewport all() {
        return ALL;
    }

    public static Viewport rows(RowRange range) {
        return new Builder().rows(range).build();
    }

    public static Viewport columns(String... cols) {
        return new Builder().columns(cols).build();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Viewport other)) {
            return false;
        }
        return rows.equals(other.rows)
                && Objects.equals(columns, other.columns)
                && Objects.equals(order, other.order)
                && limit.equals(other.limit);
    }

    @Override
    public int hashCode() {
        return Objects.hash(rows, columns, order, limit);
    }

    @Override
    public String toString() {
        return "Viewport{rows=" + rows
                + ", columns=" + (columns == null ? "*" : columns)
                + ", order=" + order
                + ", limit=" + (limit.isPresent() ? limit.getAsLong() : "-")
                + '}';
    }

    public static final class Builder {
        private RowRange rows = RowRange.ALL;
        private Set<String> columns;
        private List<SortKey> order;
        private OptionalLong limit = OptionalLong.empty();

        public Builder rows(RowRange r) {
            this.rows = r;
            return this;
        }

        public Builder rows(long from, long to) {
            this.rows = new RowRange(from, to);
            return this;
        }

        public Builder columns(String... cols) {
            this.columns = new LinkedHashSet<>(List.of(cols));
            return this;
        }

        public Builder columns(Set<String> cols) {
            this.columns = cols == null ? null : new LinkedHashSet<>(cols);
            return this;
        }

        public Builder order(List<SortKey> keys) {
            this.order = keys == null ? null : List.copyOf(keys);
            return this;
        }

        public Builder limit(long n) {
            if (n < 0) throw new IllegalArgumentException("limit must be non-negative: " + n);
            this.limit = OptionalLong.of(n);
            return this;
        }

        public Viewport build() {
            return new Viewport(rows, columns, order, limit);
        }
    }

    /** Single sort key: column name + direction. */
    public record SortKey(String column, Direction direction) {
        public enum Direction { ASC, DESC }

        public static SortKey asc(String col) {
            return new SortKey(col, Direction.ASC);
        }

        public static SortKey desc(String col) {
            return new SortKey(col, Direction.DESC);
        }
    }
}
