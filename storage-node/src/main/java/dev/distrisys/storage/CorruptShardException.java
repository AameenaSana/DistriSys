package dev.distrisys.storage;

/**
 * A shard exists on disk but cannot be trusted (bad magic, checksum mismatch, truncation...).
 * Callers must treat it exactly like a missing shard: never serve its bytes.
 */
public final class CorruptShardException extends Exception {
    public CorruptShardException(String message) {
        super(message);
    }
}
