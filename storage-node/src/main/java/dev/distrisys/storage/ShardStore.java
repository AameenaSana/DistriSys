package dev.distrisys.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.zip.CRC32C;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shards on local disk. Layout:
 * <pre>
 * {dataDir}/shards/{2-hex-digit bucket}/{versionId}_{stripe}_{shard}.shard
 * </pre>
 * The 256 bucket directories keep any single directory from growing to millions of entries.
 * They are created once at startup, so writes never create directories (which would need
 * an extra fsync of the parent directory).
 */
public final class ShardStore {

    private static final Logger log = LoggerFactory.getLogger(ShardStore.class);
    private static final String TEMP_SUFFIX = ".tmp";

    private final Path shardsDir;

    private ShardStore(Path shardsDir) {
        this.shardsDir = shardsDir;
    }

    public static ShardStore open(Path dataDir) throws IOException {
        Path shardsDir = dataDir.resolve("shards");
        Files.createDirectories(shardsDir);
        for (int bucket = 0; bucket < 256; bucket++) {
            Files.createDirectories(shardsDir.resolve(bucketName(bucket)));
        }
        Durability.fsyncDirectory(shardsDir);
        Durability.fsyncDirectory(dataDir);

        ShardStore store = new ShardStore(shardsDir);
        int removed = store.deleteTempFiles();
        log.info("Opened shard store at {} (removed {} leftover temp files)", shardsDir, removed);
        return store;
    }

    /**
     * Starts writing a shard. The caller declares the length and CRC32C up front; the writer
     * rejects the shard on commit if the bytes it actually received don't match.
     */
    public ShardWriter beginWrite(ShardKey key, long payloadLength, int payloadCrc32c)
            throws IOException, ShardWriteRejectedException {
        if (payloadLength < 0 || payloadLength > ShardHeader.MAX_PAYLOAD_BYTES) {
            throw new ShardWriteRejectedException("shard " + key + ": invalid length " + payloadLength);
        }
        Path finalPath = pathFor(key);
        // Random suffix: two concurrent writes of the same shard (e.g. a retry racing the original)
        // each get their own temp file. Whichever commits last wins; both hold identical bytes.
        String unique = HexFormat.of().toHexDigits(ThreadLocalRandom.current().nextLong());
        Path tempPath = finalPath.resolveSibling(finalPath.getFileName() + "." + unique + TEMP_SUFFIX);
        return new ShardWriter(new ShardHeader(key, payloadLength, payloadCrc32c), tempPath, finalPath);
    }

    /**
     * Reads and verifies a shard. Returns empty if it doesn't exist; throws if it exists but is corrupt.
     * Loads the whole payload into memory, which is fine because shards are bounded (256 KB in practice,
     * {@link ShardHeader#MAX_PAYLOAD_BYTES} at most), and lets us verify before sending a single byte.
     */
    public Optional<StoredShard> read(ShardKey key) throws IOException, CorruptShardException {
        Path path = pathFor(key);
        try (FileChannel ch = FileChannel.open(path, StandardOpenOption.READ)) {
            ShardHeader header = ShardHeader.read(ch);
            if (!header.key().equals(key)) {
                // e.g. a file copied or renamed by hand: the name says one shard, the contents another.
                throw new CorruptShardException("file " + path.getFileName() + " contains shard " + header.key());
            }
            long expectedSize = header.encodedSize() + header.payloadLength();
            if (ch.size() != expectedSize) {
                throw new CorruptShardException("shard " + key + ": file is " + ch.size()
                        + " bytes, expected " + expectedSize);
            }
            ByteBuffer payload = ByteBuffer.allocate((int) header.payloadLength());
            ShardHeader.readFully(ch, payload, header.encodedSize());

            CRC32C crc = new CRC32C();
            crc.update(payload.array());
            if ((int) crc.getValue() != header.payloadCrc32c()) {
                throw new CorruptShardException("shard " + key + ": payload checksum mismatch");
            }
            return Optional.of(new StoredShard(key, payload.array(), header.payloadCrc32c()));
        } catch (NoSuchFileException e) {
            return Optional.empty();
        }
    }

    Path pathFor(ShardKey key) {
        return shardsDir.resolve(bucketName(bucketOf(key.versionId()))).resolve(key.fileName());
    }

    /** All shards of one version share a bucket, so a node's shards for an object are listed together. */
    private static int bucketOf(String versionId) {
        CRC32C crc = new CRC32C();
        crc.update(versionId.getBytes(StandardCharsets.US_ASCII));
        return (int) (crc.getValue() & 0xFF);
    }

    private static String bucketName(int bucket) {
        return HexFormat.of().toHexDigits((byte) bucket);
    }

    /** Temp files can only be left behind by a crash mid-write; none of them was ever acknowledged. */
    private int deleteTempFiles() throws IOException {
        int removed = 0;
        try (DirectoryStream<Path> buckets = Files.newDirectoryStream(shardsDir)) {
            for (Path bucket : buckets) {
                try (DirectoryStream<Path> temps = Files.newDirectoryStream(bucket, "*" + TEMP_SUFFIX)) {
                    for (Path temp : temps) {
                        Files.delete(temp);
                        removed++;
                    }
                }
            }
        }
        return removed;
    }
}
