package io.columnar.memory;

import io.columnar.core.chunk.HotDoubleChunk;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Tracks HOT {@link HotDoubleChunk} footprint and evicts cold chunks to WARM
 * {@link WarmDoubleChunk} using an access-ordered LRU. {@link #pin(long)} promotes
 * WARM → HOT (copy) and prevents eviction until every pin is closed.
 *
 * <p>This is intentionally scoped to double columns — the plan calls for parallel paths
 * per primitive type; the layout + manager pattern duplicates cleanly.
 */
public final class ChunkResidencyManager implements AutoCloseable {

    private final Arena warmArena;
    private final long maxHotBytes;
    private long hotBytes;

    private final Map<Long, Tracked> byId = new LinkedHashMap<>(256, 0.75f, true);
    private long nextId = 1L;

    public ChunkResidencyManager(long maxHotBytes) {
        if (maxHotBytes < 0) {
            throw new IllegalArgumentException("maxHotBytes=" + maxHotBytes);
        }
        this.maxHotBytes = maxHotBytes;
        this.warmArena = Arena.ofShared();
    }

    public long maxHotBytes() {
        return maxHotBytes;
    }

    public long hotBytes() {
        return hotBytes;
    }

    /**
     * Register a HOT chunk for tracking. Returns an opaque id used for {@link #pin(long)}
     * and {@link #currentChunk(long)}.
     */
    public long admit(HotDoubleChunk hot) {
        Objects.requireNonNull(hot, "hot");
        long id = nextId++;
        Tracked t = new Tracked(id, hot);
        synchronized (this) {
            byId.put(id, t);
            hotBytes += t.hotBytes();
            evictWhileOverBudget();
        }
        return id;
    }

    /** Current materialized representation (HOT or WARM). */
    public io.columnar.core.chunk.DoubleChunk currentChunk(long id) {
        synchronized (this) {
            Tracked t = byId.get(id);
            if (t == null) {
                throw new IllegalArgumentException("unknown chunk id: " + id);
            }
            return t.active();
        }
    }

    /**
     * Ensure the chunk is HOT for the duration of the returned pin. When the chunk was
     * WARM, this allocates a fresh HOT copy and accounts it toward the HOT budget.
     */
    public ChunkPin pin(long id) {
        synchronized (this) {
            Tracked t = byId.get(id);
            if (t == null) {
                throw new IllegalArgumentException("unknown chunk id: " + id);
            }
            t.ensureHot(this);
            t.pinCount++;
            final Tracked tracked = t;
            return () -> {
                synchronized (ChunkResidencyManager.this) {
                    tracked.pinCount--;
                    evictWhileOverBudget();
                }
            };
        }
    }

    /** Touch LRU order without changing residency. */
    public void touch(long id) {
        synchronized (this) {
            Tracked t = byId.get(id);
            if (t == null) {
                throw new IllegalArgumentException("unknown chunk id: " + id);
            }
            byId.get(id); // LinkedHashMap access-order bump
        }
    }

    /**
     * Remove tracking for a chunk id and release any native memory held for its WARM form.
     * HOT arrays are not freed here — they are owned by the {@link HotDoubleChunk}.
     */
    public void remove(long id) {
        synchronized (this) {
            Tracked t = byId.remove(id);
            if (t == null) {
                return;
            }
            if (t.hot != null) {
                hotBytes -= t.hotBytes();
            }
        }
    }

    @Override
    public void close() {
        warmArena.close();
    }

    void evictWhileOverBudget() {
        while (hotBytes > maxHotBytes) {
            Tracked victim = null;
            for (Tracked t : byId.values()) {
                if (t.hot != null && t.pinCount == 0) {
                    victim = t;
                    break; // eldest with HOT in access-ordered map
                }
            }
            if (victim == null) {
                return; // everything pinned or already WARM
            }
            victim.demoteToWarm(warmArena, this);
        }
    }

    static final class Tracked {
        final long id;
        HotDoubleChunk hot;
        WarmDoubleChunk warm;
        int pinCount;

        Tracked(long id, HotDoubleChunk hot) {
            this.id = id;
            this.hot = hot;
            this.warm = null;
        }

        io.columnar.core.chunk.DoubleChunk active() {
            if (hot != null) {
                return hot;
            }
            if (warm != null) {
                return warm;
            }
            throw new IllegalStateException("chunk id=" + id + " has no storage");
        }

        long hotBytes() {
            return hot == null ? 0L : hotFootprint();
        }

        long hotFootprint() {
            if (hot == null) {
                return 0L;
            }
            // values array + validity words (approximate RAW heap usage)
            long vals = (long) hot.size() * Double.BYTES;
            long bits = (long) ((hot.size() + 63) >>> 6) * Long.BYTES;
            // HotDoubleChunk object header ignored — constant per chunk.
            return vals + bits;
        }

        void ensureHot(ChunkResidencyManager mgr) {
            if (hot != null) {
                mgr.byId.get(id); // bump LRU
                return;
            }
            HotDoubleChunk promoted = Objects.requireNonNull(warm, "warm").promoteToHot();
            warm = null;
            hot = promoted;
            mgr.hotBytes += hotFootprint();
            mgr.byId.get(id);
            mgr.evictWhileOverBudget();
        }

        void demoteToWarm(Arena arena, ChunkResidencyManager mgr) {
            if (hot == null) {
                return;
            }
            long footprint = hotFootprint();
            MemorySegment seg =
                    DoubleColdLayout.allocateAndWrite(arena, hot.values(), hot.size(), hot.validity());
            warm = WarmDoubleChunk.fromHot(seg);
            mgr.hotBytes -= footprint;
            hot = null;
        }
    }
}
