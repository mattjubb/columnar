package io.columnar.core.store;

import io.columnar.core.DataType;
import io.columnar.core.Schema;

/** Factory for creating type-appropriate {@link ColumnStore}s from a {@link Schema} field. */
public final class ColumnStores {

    private ColumnStores() {}

    public static ColumnStore create(String name, DataType type) {
        if (type == DataType.DOUBLE_ARRAY) {
            throw new IllegalArgumentException(
                    "DOUBLE_ARRAY requires array length; use create(Schema.Field) or create(name, type, arrayLength)");
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
            case OBJECT -> throw new UnsupportedOperationException(
                    "OBJECT column type not yet implemented");
        };
    }

    public static ColumnStore create(Schema.Field field) {
        if (field.type() == DataType.DOUBLE_ARRAY) {
            return new DoubleArrayColumnStore(field.name(), field.arrayLength());
        }
        return create(field.name(), field.type());
    }
}
