package dev.distrisys.storage;

import java.util.regex.Pattern;

/**
 * Identifies one shard on disk. Mirrors the wire-level {@code ShardId}, but is deliberately
 * independent of protobuf so the on-disk format never changes just because the wire format did.
 */
public record ShardKey(String versionId, int stripeIndex, int shardIndex) {

    /**
     * Version ids become part of file names, so they are restricted to a safe alphabet.
     * A malicious or buggy id like "../../etc/passwd" must never escape the data directory.
     * No underscore either: it is our separator in file names.
     */
    private static final Pattern VERSION_ID = Pattern.compile("[A-Za-z0-9-]{1,128}");

    public ShardKey {
        if (versionId == null || !VERSION_ID.matcher(versionId).matches()) {
            throw new IllegalArgumentException("invalid versionId: " + versionId);
        }
        if (stripeIndex < 0) {
            throw new IllegalArgumentException("negative stripeIndex: " + stripeIndex);
        }
        if (shardIndex < 0 || shardIndex > 255) {
            throw new IllegalArgumentException("shardIndex out of range: " + shardIndex);
        }
    }

    String fileName() {
        return versionId + "_" + stripeIndex + "_" + shardIndex + ".shard";
    }

    @Override
    public String toString() {
        return versionId + "/" + stripeIndex + "/" + shardIndex;
    }
}
