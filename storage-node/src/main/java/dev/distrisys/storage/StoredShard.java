package dev.distrisys.storage;

/** A shard read from disk whose header and payload checksums have both been verified. */
public record StoredShard(ShardKey key, byte[] payload, int crc32c) {
}
