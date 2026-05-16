package io.columnar.expr;

import java.util.LinkedHashSet;
import java.util.Set;

final class RequiredColumnCollector {

    private RequiredColumnCollector() {}

    static Set<String> collect(Expr expr) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        visit(expr, out);
        return out;
    }

    private static void visit(Expr e, LinkedHashSet<String> out) {
        switch (e) {
            case Expr.ColRef c -> out.add(c.name());
            case Expr.Const ignored -> {}
            case Expr.Unary u -> visit(u.child(), out);
            case Expr.Binary b -> {
                visit(b.left(), out);
                visit(b.right(), out);
            }
            case Expr.Call call -> call.args().forEach(a -> visit(a, out));
        }
    }
}
