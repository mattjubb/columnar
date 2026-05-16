package io.columnar.memory;

/**
 * RAII handle returned from {@link ChunkResidencyManager#pin(long)}. Closing decrements
 * the pin count so LRU eviction can demote the chunk again.
 */
public interface ChunkPin extends AutoCloseable {

    @Override
    void close();
}
