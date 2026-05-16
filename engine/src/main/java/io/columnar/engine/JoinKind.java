package io.columnar.engine;

/** Hash-join shape supported by {@link HashJoinOperator}. */
public enum JoinKind {
    INNER,
    LEFT,
    FULL,
    RIGHT
}
