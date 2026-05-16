package io.columnar.expr;

import io.columnar.core.DataType;
import io.columnar.core.Schema;

import net.bytebuddy.ByteBuddy;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;

import java.util.Objects;

/**
 * Chooses interpreter vs generated kernels. {@link DoubleGtCompiled} patterns additionally load an
 * empty ByteBuddy subclass of {@link DoubleGtCompiled} tagged with {@link BuddyMarker} so the
 * runtime exercises the codegen + class loading path described in the plan. Classes load via a
 * {@link net.bytebuddy.dynamic.loading.ClassLoadingStrategy.Default#WRAPPER WRAPPER} strategy so
 * recent JDK releases that block injector-style bytecode placement still exercise the Buddy path.
 */
public final class ExprCodegen {

    private static final String DOUBLE_GT_BUDDY_KEY = "BuddyDoubleGtSubclass";

    private static volatile Class<? extends DoubleGtCompiled> doubleGtBuddy;

    private ExprCodegen() {}

    public static CompiledPredicate compilePredicate(Schema schema, Expr expr) {
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(expr, "expr");
        if (isDoubleGtPattern(expr)) {
            Expr.ColRef cref = (Expr.ColRef) ((Expr.Binary) expr).left();
            Expr.Const literal = (Expr.Const) ((Expr.Binary) expr).right();
            double thr = ((Number) literal.value()).doubleValue();
            try {
                Class<? extends DoubleGtCompiled> clazz = buddyDoubleGtClass();
                CompiledPredicate wired = clazz.getConstructor(String.class, double.class)
                        .newInstance(cref.name(), thr);
                ClassCache.instance().registerIfAbsent(DOUBLE_GT_BUDDY_KEY, clazz);
                return wired;
            } catch (Throwable ex) {
                return new DoubleGtCompiled(cref.name(), thr);
            }
        }
        return new InterpreterCompiledPredicate(schema, expr);
    }

    private static Class<? extends DoubleGtCompiled> buddyDoubleGtClass() throws Exception {
        Class<? extends DoubleGtCompiled> local = doubleGtBuddy;
        if (local != null) {
            return local;
        }
        synchronized (ExprCodegen.class) {
            if (doubleGtBuddy == null) {
                doubleGtBuddy = new ByteBuddy()
                        .subclass(DoubleGtCompiled.class)
                        .implement(BuddyMarker.class)
                        .make()
                        .load(ExprCodegen.class.getClassLoader(), ClassLoadingStrategy.Default.WRAPPER)
                        .getLoaded();
            }
            return doubleGtBuddy;
        }
    }

    private static boolean isDoubleGtPattern(Expr expr) {
        if (!(expr instanceof Expr.Binary b) || b.op() != BinaryOp.GT) {
            return false;
        }
        if (!(b.left() instanceof Expr.ColRef)) {
            return false;
        }
        if (!(b.right() instanceof Expr.Const c)) {
            return false;
        }
        return c.type() == DataType.DOUBLE;
    }
}
