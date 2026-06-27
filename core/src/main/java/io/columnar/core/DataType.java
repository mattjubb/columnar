package io.columnar.core;

/**
 * Logical data types supported by the framework. Each constant carries its
 * fixed byte width when applicable; variable-width types (STRING, OBJECT) report 0.
 */
public enum DataType {
    INT(4),
    LONG(8),
    FLOAT(4),
    DOUBLE(8),
    BOOLEAN(0),   // packed 1 bit per row; stored separately
    STRING(0),    // dictionary-encoded; codes are int but logical width is variable
    INSTANT(8),   // epoch nanos as long
    /** Fixed-length {@code double[]} per row; width is {@link Schema.Field#arrayLength()}. */
    DOUBLE_ARRAY(0),
    /** Fixed-length {@code int[]} per row; length is {@link Schema.Field#arrayLength()}. */
    INT_ARRAY(0),
    /** Fixed-length {@code String[]} per row; length is {@link Schema.Field#arrayLength()}. */
    STRING_ARRAY(0),
    /** Fixed-length array of dates (epoch nanos as {@code long[]}) per row; length is {@link Schema.Field#arrayLength()}. */
    DATE_ARRAY(0),
    OBJECT(0);    // boxed fallback

    private final int byteWidth;

    DataType(int byteWidth) {
        this.byteWidth = byteWidth;
    }

    /** Bytes per value for fixed-width numeric types. {@code 0} for variable / packed. */
    public int byteWidth() {
        return byteWidth;
    }

    public boolean isFixedWidth() {
        return byteWidth > 0;
    }
}
