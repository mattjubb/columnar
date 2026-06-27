package io.columnar.api.io;

/** Supported serialization formats for {@link TableIO}. */
public enum Format {
    /** RFC 4180 comma-separated values. Schema is inferred on read unless provided. */
    CSV,
    /** Apache Arrow IPC file format (magic + schema + record batches). */
    ARROW,
    /** Apache Parquet columnar file format. */
    PARQUET
}
