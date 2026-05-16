package io.columnar.core;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntMaps;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * An ordered list of named, typed fields. Immutable.
 */
public final class Schema {

    public record Field(String name, DataType type, int arrayLength) {
        public Field(String name, DataType type) {
            this(name, type, 0);
        }

        public Field {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(type, "type");
            if (name.isEmpty()) {
                throw new IllegalArgumentException("field name must not be empty");
            }
            if (type == DataType.DOUBLE_ARRAY) {
                if (arrayLength <= 0) {
                    throw new IllegalArgumentException(
                            "DOUBLE_ARRAY field " + name + " requires arrayLength > 0");
                }
            } else if (arrayLength != 0) {
                throw new IllegalArgumentException(
                        "arrayLength applies only to DOUBLE_ARRAY, got " + type + " for " + name);
            }
        }
    }

    private final List<Field> fields;
    /** Primitive int map, defaultReturnValue = -1 for "not present". */
    private final Object2IntMap<String> indexByName;

    private Schema(List<Field> fields) {
        this.fields = List.copyOf(fields);
        Object2IntOpenHashMap<String> idx = new Object2IntOpenHashMap<>(fields.size() * 2);
        idx.defaultReturnValue(-1);
        for (int i = 0; i < this.fields.size(); i++) {
            Field f = this.fields.get(i);
            int prev = idx.put(f.name(), i);
            if (prev != -1) {
                throw new IllegalArgumentException("duplicate field name: " + f.name());
            }
        }
        this.indexByName = Object2IntMaps.unmodifiable(idx);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static Schema of(Field... fields) {
        return new Schema(List.of(fields));
    }

    public List<Field> fields() {
        return fields;
    }

    public int size() {
        return fields.size();
    }

    public Field field(int idx) {
        return fields.get(idx);
    }

    public Field field(String name) {
        int idx = indexByName.getInt(name);
        if (idx < 0) {
            throw new IllegalArgumentException("no such field: " + name);
        }
        return fields.get(idx);
    }

    public int indexOf(String name) {
        int idx = indexByName.getInt(name);
        if (idx < 0) {
            throw new IllegalArgumentException("no such field: " + name);
        }
        return idx;
    }

    public boolean contains(String name) {
        return indexByName.getInt(name) >= 0;
    }

    public List<String> names() {
        return fields.stream().map(Field::name).toList();
    }

    /** Project this schema down to a subset (and possibly reordered) set of names. */
    public Schema select(List<String> names) {
        List<Field> picked = new ArrayList<>(names.size());
        for (String n : names) {
            picked.add(field(n));
        }
        return new Schema(picked);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Schema s && fields.equals(s.fields);
    }

    @Override
    public int hashCode() {
        return fields.hashCode();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("Schema{");
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) sb.append(", ");
            Field f = fields.get(i);
            sb.append(f.name()).append(':').append(f.type());
            if (f.arrayLength() > 0) {
                sb.append('[').append(f.arrayLength()).append(']');
            }
        }
        return sb.append('}').toString();
    }

    public static final class Builder {
        private final List<Field> fields = new ArrayList<>();

        public Builder add(String name, DataType type) {
            fields.add(new Field(name, type));
            return this;
        }

        /**
         * Add a fixed-length primitive array column (currently {@link DataType#DOUBLE_ARRAY} only).
         */
        public Builder add(String name, DataType type, int arrayLength) {
            fields.add(new Field(name, type, arrayLength));
            return this;
        }

        public Schema build() {
            if (fields.isEmpty()) {
                throw new IllegalStateException("schema must have at least one field");
            }
            return new Schema(fields);
        }
    }
}
