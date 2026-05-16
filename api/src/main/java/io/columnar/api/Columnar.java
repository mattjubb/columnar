package io.columnar.api;

import io.columnar.core.Table;
import io.columnar.engine.DerivedTable;
import io.columnar.engine.ExprRowPredicate;
import io.columnar.engine.FilterOperator;
import io.columnar.engine.Operator;
import io.columnar.engine.RowPredicate;
import io.columnar.engine.SourceOperator;
import io.columnar.expr.Expr;
import io.columnar.query.CachedDerived;

/**
 * Fluent entry points that lift {@link Table} instances into planner-friendly operator graphs.
 *
 * <p>For heavier pull caching use {@link #filterCached(Expr, String)} which wires {@link CachedDerived}.
 */
public final class Columnar {

    private Columnar() {}

    public static QueryPlanner from(Table table) {
        return new QueryPlanner(table);
    }

    /** Planner snapshot bound to a concrete source {@link Table}. */
    public static final class QueryPlanner {
        private final Table table;

        private QueryPlanner(Table table) {
            this.table = table;
        }

        private SourceOperator source() {
            return new SourceOperator(table);
        }

        public DerivedTable filter(String predicateNameHint, RowPredicate predicate) {
            return new DerivedTable(new FilterOperator(source(), predicate, predicateNameHint));
        }

        /** Expression-backed filters compile hot shapes via {@link io.columnar.expr.ExprCodegen}. */
        public DerivedTable filterExpr(String predicateNameHint, Expr expr) {
            return new DerivedTable(
                    new FilterOperator(source(), new ExprRowPredicate(table.schema(), expr), predicateNameHint));
        }

        public CachedDerived filterCached(Expr expr, String predicateNameHint) {
            Operator op =
                    new FilterOperator(
                            source(),
                            new ExprRowPredicate(table.schema(), expr),
                            predicateNameHint + "-cached-pull");
            return CachedDerived.of(op);
        }

        public Table table() {
            return table;
        }
    }
}
