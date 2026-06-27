package io.columnar.api.io;

import io.columnar.api.Table;

import java.io.IOException;
import java.nio.file.Path;

/** Writes a {@link Table} to a file in a specific {@link Format}. */
@FunctionalInterface
public interface TableWriter {
    void write(Table table, Path path) throws IOException;
}
