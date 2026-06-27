package io.columnar.api.io;

import io.columnar.core.BaseTable;
import io.columnar.core.Schema;

import java.io.IOException;
import java.nio.file.Path;

/** Reads a {@link io.columnar.core.Table} from a file in a specific {@link Format}. */
public interface TableReader {
    /**
     * Read a table from {@code path}. For self-describing formats (Arrow, Parquet) the schema
     * is embedded; for CSV the column types are inferred from the data.
     */
    BaseTable read(Path path) throws IOException;

    /**
     * Read a table from {@code path} using {@code schema} to drive type parsing.
     * Useful for CSV where the file itself carries no type information.
     */
    default BaseTable read(Path path, Schema schema) throws IOException {
        return read(path);
    }
}
