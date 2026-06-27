package io.columnar.api;

/** Hash-join shape supported by {@link HashJoinOperator}. */
public enum JoinKind {
    INNER,
    LEFT,
    FULL,
    RIGHT
}
