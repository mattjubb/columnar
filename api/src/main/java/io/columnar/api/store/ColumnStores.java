package io.columnar.api.store;

import io.columnar.api.DataType;
import io.columnar.api.Schema;

import static io.columnar.api.DataType.DATE_ARRAY;
import static io.columnar.api.DataType.INT_ARRAY;
import static io.columnar.api.DataType.STRING_ARRAY;

/** Factory for creating type-appropriate {@link ColumnStore}s from a {@link Schema} field. */
public final class ColumnStores {

    private ColumnStores() {}

    public static ColumnStore create(String name, DataType type) {
        if (type == DataType.DOUBLE_ARRAY || type == INT_ARRAY
                || type == STRING_ARRAY || type == DATE_ARRAY) {
            throw new IllegalArgumentException(
                    type + " requires array length; use create(Schema.Field) or create(name, type, arrayLength)");
        }
        return create(name, type, 0);
    }

    public static ColumnStore create(String name, DataType type, int arrayLength) {
        return switch (type) {
            case INT -> new IntColumnStore(name);
            case LONG -> new LongColumnStore(name);
            case FLOAT -> new FloatColumnStore(name);
            case DOUBLE -> new DoubleColumnStore(name);
            case BOOLEAN -> new BooleanColumnStore(name);
            case STRING -> new StringColumnStore(name);
            case INSTANT -> new InstantColumnStore(name);
            case DOUBLE_ARRAY -> new DoubleArrayColumnStore(name, arrayLength);
            case INT_ARRAY -> new IntArrayColumnStore(name, arrayLength);
            case STRING_ARRAY -> new StringArrayColumnStore(name, arrayLength);
            case DATE_ARRAY -> new DateArrayColumnStore(name, arrayLength);
            case OBJECT -> throw new UnsupportedOperationException(
                    "OBJECT column type not yet implemented");
        };
    }

    public static ColumnStore create(Schema.Field field) {
        return switch (field.type()) {
            case DOUBLE_ARRAY -> new DoubleArrayColumnStore(field.name(), field.arrayLength());
            case INT_ARRAY -> new IntArrayColumnStore(field.name(), field.arrayLength());
            case STRING_ARRAY -> new StringArrayColumnStore(field.name(), field.arrayLength());
            case DATE_ARRAY -> new DateArrayColumnStore(field.name(), field.arrayLength());
            default -> create(field.name(), field.type());
        };
    }
}
