package io.columnar.api.io;

import io.columnar.api.BaseTable;
import io.columnar.api.Schema;
import io.columnar.api.Table;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Static entry point for reading and writing tables in various {@link Format}s.
 *
 * <pre>{@code
 * // write
 * TableIO.write(table, Format.CSV,     Path.of("out.csv"));
 * TableIO.write(table, Format.ARROW,   Path.of("out.arrow"));
 * TableIO.write(table, Format.PARQUET, Path.of("out.parquet"));
 *
 * // read (self-describing formats)
 * BaseTable t = TableIO.read(Format.ARROW,   Path.of("in.arrow"));
 * BaseTable t = TableIO.read(Format.PARQUET, Path.of("in.parquet"));
 *
 * // read CSV with an explicit schema
 * BaseTable t = TableIO.read(Format.CSV, Path.of("in.csv"), schema);
 * }</pre>
 *
 * <p>Supported scalar types: {@code INT, LONG, FLOAT, DOUBLE, BOOLEAN, STRING, INSTANT}.
 * Array types and {@code OBJECT} are not supported by any format.
 */
public final class TableIO {

    private TableIO() {}

    public static void write(Table table, Format format, Path path) throws IOException {
        writer(format).write(table, path);
    }

    public static BaseTable read(Format format, Path path) throws IOException {
        return reader(format).read(path);
    }

    public static BaseTable read(Format format, Path path, Schema schema) throws IOException {
        return reader(format).read(path, schema);
    }

    public static TableWriter writer(Format format) {
        return switch (format) {
            case CSV     -> new CsvFormat();
            case ARROW   -> new ArrowFormat();
            case PARQUET -> new ParquetFormat();
        };
    }

    public static TableReader reader(Format format) {
        return switch (format) {
            case CSV     -> new CsvFormat();
            case ARROW   -> new ArrowFormat();
            case PARQUET -> new ParquetFormat();
        };
    }
}
