package dev.distrisys.storage;

/** The client sent a shard that doesn't match what it declared (length or checksum). Nothing was stored. */
public final class ShardWriteRejectedException extends Exception {
    public ShardWriteRejectedException(String message) {
        super(message);
    }
}
