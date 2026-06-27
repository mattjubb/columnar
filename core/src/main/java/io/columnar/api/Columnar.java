package io.columnar.api;

import io.columnar.api.io.Format;
import io.columnar.api.io.TableIO;
import io.columnar.api.BaseTable;
import io.columnar.api.Schema;
import io.columnar.api.Table;
import io.columnar.core.DerivedTable;
import io.columnar.core.ExprRowPredicate;
import io.columnar.core.FilterOperator;
import io.columnar.core.HashAggregateOperator;
import io.columnar.core.HashAggregateOperator.AggMeasure;
import io.columnar.core.HashJoinOperator;
import io.columnar.api.JoinKind;
import io.columnar.core.Operator;
import io.columnar.core.OrderByOperator;
import io.columnar.core.PivotOperator;
import io.columnar.core.ProjectOperator;
import io.columnar.api.RowPredicate;
import io.columnar.core.RowPredicates;
import io.columnar.core.SourceOperator;
import io.columnar.api.Expr;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * Single entry point for the columnar framework.
 *
 * <h2>Table construction</h2>
 * <pre>{@code
 * Schema schema = Columnar.schema()
 *     .add("id",    DataType.LONG)
 *     .add("name",  DataType.STRING)
 *     .add("price", DataType.DOUBLE)
 *     .build();
 *
 * // sealed static table
 * BaseTable table = Columnar.table(schema)
 *     .appendRow(1L, "Apple",  0.99)
 *     .appendRow(2L, "Banana", 0.49)
 *     .build();
 *
 * // live table – rows appended after construction
 * BaseTable live = Columnar.liveTable(schema);
 * live.appendRow(1L, "Apple", 0.99);
 * }</pre>
 *
 * <h2>Query building</h2>
 * <pre>{@code
 * DerivedTable result = Columnar.from(table)
 *     .filter("price > 0.5", Columnar.doubleGt("price", 0.5))
 *     .project("name", "price")
 *     .orderBy("price", false)   // descending
 *     .build();
 *
 * ColumnarSlice slice = result.read();
 * }</pre>
 *
 * <h2>Aggregation</h2>
 * <pre>{@code
 * DerivedTable agg = Columnar.from(table)
 *     .groupBy("category", Columnar.count("cnt"), Columnar.sum("amount", "total"))
 *     .build();
 * }</pre>
 *
 * <h2>Joining</h2>
 * <pre>{@code
 * DerivedTable joined = Columnar.from(orders)
 *     .join(customers, "customer_id", "id", JoinKind.INNER)
 *     .build();
 * }</pre>
 *
 * <h2>File I/O</h2>
 * <pre>{@code
 * Columnar.write(table, Format.PARQUET, Path.of("data.parquet"));
 * BaseTable loaded = Columnar.read(Format.PARQUET, Path.of("data.parquet"));
 * }</pre>
 */
public final class Columnar {

    private Columnar() {}

    // =========================================================================
    // Table construction
    // =========================================================================

    /**
     * Start building a sealed static table.
     * Call {@link BaseTable.Builder#appendRow} for each row then {@link BaseTable.Builder#build()}.
     */
    public static BaseTable.Builder table(Schema schema) {
        return Table.builder(schema);
    }

    /**
     * Create an open (live-append) table whose rows can be added at any time.
     * The table starts empty and can be read while being written.
     */
    public static BaseTable liveTable(Schema schema) {
        return Table.create(schema);
    }

    /** Start building a {@link Schema}. */
    public static Schema.Builder schema() {
        return Schema.builder();
    }

    // =========================================================================
    // File I/O
    // =========================================================================

    /**
     * Read a table from {@code path} in the given format.
     * For self-describing formats (Arrow, Parquet) the schema is embedded in the file.
     * For CSV, column types are inferred from the data.
     */
    public static BaseTable read(Format format, Path path) throws IOException {
        return TableIO.read(format, path);
    }

    /**
     * Read a table from {@code path}, using {@code schema} to drive type parsing.
     * Most useful for CSV where the file itself carries no type information.
     */
    public static BaseTable read(Format format, Path path, Schema schema) throws IOException {
        return TableIO.read(format, path, schema);
    }

    /** Write {@code table} to {@code path} in the given format. */
    public static void write(Table table, Format format, Path path) throws IOException {
        TableIO.write(table, format, path);
    }

    // =========================================================================
    // Predicate factories
    // =========================================================================

    /** {@code column > threshold} predicate on a LONG column. */
    public static RowPredicate longGt(String column, long threshold) {
        return RowPredicates.longGt(column, threshold);
    }

    /** {@code column == value} predicate on a LONG column. */
    public static RowPredicate longEq(String column, long value) {
        return RowPredicates.longEq(column, value);
    }

    /** {@code column > threshold} predicate on a DOUBLE column. */
    public static RowPredicate doubleGt(String column, double threshold) {
        return RowPredicates.doubleGt(column, threshold);
    }

    /** {@code column == value} predicate on a STRING column. */
    public static RowPredicate stringEq(String column, String value) {
        return RowPredicates.stringEq(column, value);
    }

    /** Short-circuit AND of two predicates. */
    public static RowPredicate and(RowPredicate a, RowPredicate b) {
        return RowPredicates.and(a, b);
    }

    /** Short-circuit OR of two predicates. */
    public static RowPredicate or(RowPredicate a, RowPredicate b) {
        return RowPredicates.or(a, b);
    }

    // =========================================================================
    // Aggregation measure factories
    // =========================================================================

    /**
     * COUNT(*) measure — counts rows in each group.
     *
     * @param outputColumn name of the output column that will hold the count (LONG)
     */
    public static AggMeasure count(String outputColumn) {
        return new AggMeasure(HashAggregateOperator.AggKind.COUNT, null, outputColumn);
    }

    /**
     * SUM measure — sums a DOUBLE column within each group.
     *
     * @param inputColumn  DOUBLE column to sum
     * @param outputColumn name of the output column (DOUBLE)
     */
    public static AggMeasure sum(String inputColumn, String outputColumn) {
        return new AggMeasure(HashAggregateOperator.AggKind.SUM_DOUBLE, inputColumn, outputColumn);
    }

    // =========================================================================
    // Query planner
    // =========================================================================

    /**
     * Begin building a query plan over {@code table}.
     * Chain calls on the returned {@link QueryPlanner} to compose operators,
     * then call {@link QueryPlanner#build()}
     * to materialise a {@link DerivedTable}.
     */
    public static QueryPlanner from(Table table) {
        return new QueryPlanner(new SourceOperator(table), table);
    }

    /**
     * Fluent operator-graph builder. All methods return a new {@code QueryPlanner}
     * with the new operator appended, so calls can be chained freely.
     *
     * <p>Nothing executes until {@link #build()} is called.
     */
    public static final class QueryPlanner {

        private final Operator root;
        /** Source table — kept for schema resolution in expression predicates. */
        private final Table sourceTable;

        private QueryPlanner(Operator root, Table sourceTable) {
            this.root = root;
            this.sourceTable = sourceTable;
        }

        // ---- filtering ------------------------------------------------------

        /**
         * Keep only rows that satisfy {@code predicate}.
         * Use {@link Columnar#longGt}, {@link Columnar#stringEq}, etc. to build predicates,
         * or compose them with {@link Columnar#and}/{@link Columnar#or}.
         *
         * @param hint short human-readable label used in signatures and diagnostics
         */
        public QueryPlanner filter(String hint, RowPredicate predicate) {
            return new QueryPlanner(new FilterOperator(root, predicate, hint), sourceTable);
        }

        /**
         * Keep only rows where the expression {@code expr} evaluates to true.
         * The expression is compiled to a vectorised bytecode predicate the first time it runs.
         *
         * @param hint short label for diagnostics
         */
        public QueryPlanner filterExpr(String hint, Expr expr) {
            return new QueryPlanner(
                    new FilterOperator(root, new ExprRowPredicate(sourceTable.schema(), expr), hint),
                    sourceTable);
        }

        // ---- projection -----------------------------------------------------

        /**
         * Retain only the named columns, dropping all others.
         * Column order in the output matches the order given here.
         */
        public QueryPlanner project(String... columns) {
            return new QueryPlanner(new ProjectOperator(root, Arrays.asList(columns)), sourceTable);
        }

        // ---- sorting --------------------------------------------------------

        /**
         * Sort rows by {@code column} in ascending order.
         * Only LONG and DOUBLE columns are supported as sort keys.
         */
        public QueryPlanner orderBy(String column) {
            return orderBy(column, true);
        }

        /**
         * Sort rows by {@code column}.
         *
         * @param ascending {@code true} for ascending (smallest first), {@code false} for descending
         */
        public QueryPlanner orderBy(String column, boolean ascending) {
            return new QueryPlanner(new OrderByOperator(root, column, ascending), sourceTable);
        }

        // ---- aggregation ----------------------------------------------------

        /**
         * Group rows by a STRING column and compute one or more measures per group.
         * Build measures with {@link Columnar#count(String)} and {@link Columnar#sum(String, String)}.
         *
         * <p>Example:
         * <pre>{@code
         * .groupBy("region", Columnar.count("n"), Columnar.sum("revenue", "total_revenue"))
         * }</pre>
         */
        public QueryPlanner groupBy(String groupColumn, AggMeasure... measures) {
            return new QueryPlanner(
                    new HashAggregateOperator(root, groupColumn, Arrays.asList(measures)),
                    sourceTable);
        }

        // ---- joining --------------------------------------------------------

        /**
         * Inner-join with {@code other} on matching STRING key columns.
         *
         * @param leftKey  column name in this (left/probe) side
         * @param rightKey column name in {@code other} (right/build) side
         */
        public QueryPlanner join(Table other, String leftKey, String rightKey) {
            return join(other, leftKey, rightKey, JoinKind.INNER);
        }

        /**
         * Join with {@code other} on matching STRING key columns.
         *
         * @param leftKey  column name in this (left/probe) side
         * @param rightKey column name in {@code other} (right/build) side
         * @param kind     {@link JoinKind#INNER}, {@link JoinKind#LEFT}, etc.
         */
        public QueryPlanner join(Table other, String leftKey, String rightKey, JoinKind kind) {
            return new QueryPlanner(
                    new HashJoinOperator(root, new SourceOperator(other), leftKey, rightKey, kind),
                    sourceTable);
        }

        // ---- pivoting -------------------------------------------------------

        /**
         * Pivot a long-format table to wide format.
         *
         * @param rowKeyColumn  column whose values become the output row identifiers
         * @param pivotColumn   column whose distinct values become output column names
         * @param valueColumn   DOUBLE column whose values are summed into each pivot cell
         * @param pivotKeys     the distinct values of {@code pivotColumn} to materialise as columns
         */
        public QueryPlanner pivot(String rowKeyColumn,
                                  String pivotColumn,
                                  String valueColumn,
                                  List<String> pivotKeys) {
            return new QueryPlanner(
                    new PivotOperator(root, rowKeyColumn, pivotColumn, valueColumn, pivotKeys),
                    sourceTable);
        }

        // ---- terminals ------------------------------------------------------

        /**
         * Materialise the plan into a lazy {@link DerivedTable}.
         * The result is recomputed on each {@code read()} call.
         * For memoized reads use {@code PullEngines.cachingDerived()} from the {@code :query} module.
         */
        public DerivedTable build() {
            return new DerivedTable(root);
        }

        /** The original source table this plan was built from. */
        public Table table() {
            return sourceTable;
        }
    }
}
