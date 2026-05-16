package io.columnar.core.store;

import io.columnar.core.Column;
import io.columnar.core.ColumnChunk;
import io.columnar.core.DataType;
import io.columnar.core.Validity;
import io.columnar.core.ValidityBuilder;
import io.columnar.core.chunk.HotStringChunk;
import io.columnar.core.chunk.StringChunk;
import io.columnar.core.chunk.StringDictionary;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;

import java.util.List;

/** Dictionary-encoded string column. Codes are dense {@code int}s; the
 * dictionary is shared across all chunks of this column. */
public final class StringColumnStore extends ColumnStore {

    private final ObjectArrayList<StringChunk> sealed = new ObjectArrayList<>();
    private final StringDictionary dictionary = new StringDictionary();
    private IntArrayList activeCodes;
    private ValidityBuilder activeValidity;

    public StringColumnStore(String name, int chunkCapacity) {
        super(name, DataType.STRING, chunkCapacity);
        this.activeCodes = new IntArrayList(chunkCapacity);
        this.activeValidity = new ValidityBuilder(chunkCapacity);
    }

    public StringColumnStore(String name) {
        this(name, ColumnChunk.DEFAULT_CAPACITY);
    }

    public StringDictionary dictionary() {
        return dictionary;
    }

    public void appendString(String value) {
        if (value == null) {
            appendNull();
            return;
        }
        if (activeCodes.size() >= chunkCapacity) sealActive();
        int code = dictionary.intern(value);
        activeCodes.add(code);
        activeValidity.appendValid();
    }

    /** Append a pre-resolved dictionary code; caller is responsible for it being valid. */
    public void appendCode(int code) {
        if (activeCodes.size() >= chunkCapacity) sealActive();
        activeCodes.add(code);
        activeValidity.appendValid();
    }

    @Override
    public void appendNull() {
        if (activeCodes.size() >= chunkCapacity) sealActive();
        activeCodes.add(-1);
        activeValidity.appendNull();
    }

    @Override
    public void append(Object value) {
        if (value == null) appendNull();
        else if (value instanceof String s) appendString(s);
        else appendString(value.toString());
    }

    @Override
    public void sealActive() {
        if (activeCodes.isEmpty()) return;
        int[] codes = activeCodes.toIntArray();
        sealed.add(new HotStringChunk(codes, codes.length, activeValidity.toValidity(), dictionary));
        activeCodes = new IntArrayList(chunkCapacity);
        activeValidity = new ValidityBuilder(chunkCapacity);
    }

    @Override
    public long size() {
        long total = 0;
        for (StringChunk c : sealed) total += c.size();
        return total + activeCodes.size();
    }

    @Override public int sealedChunkCount() { return sealed.size(); }
    @Override public int activeSize() { return activeCodes.size(); }
    @Override public List<StringChunk> sealedChunks() { return sealed; }

    @Override
    public Column snapshot() {
        ObjectArrayList<ColumnChunk> all = new ObjectArrayList<>(sealed.size() + 1);
        all.addAll(sealed);
        if (!activeCodes.isEmpty()) {
            int[] copy = activeCodes.toIntArray();
            Validity vSnap = activeValidity.toValidity();
            all.add(new HotStringChunk(copy, copy.length, vSnap, dictionary));
        }
        return Column.of(name, DataType.STRING, all);
    }
}
