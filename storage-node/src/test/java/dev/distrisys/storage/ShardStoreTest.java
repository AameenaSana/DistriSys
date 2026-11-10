package dev.distrisys.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Random;
import java.util.zip.CRC32C;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ShardStoreTest {

    private static final ShardKey KEY = new ShardKey("v-0001", 3, 5);

    @TempDir
    Path dataDir;

    private ShardStore store;

    @BeforeEach
    void setUp() throws IOException {
        store = ShardStore.open(dataDir);
    }

    // ---- happy path -------------------------------------------------------

    @Test
    void writeThenReadRoundTrips() throws Exception {
        byte[] data = randomBytes(256 * 1024);
        write(KEY, data);

        StoredShard shard = store.read(KEY).orElseThrow();
        assertThat(shard.payload()).isEqualTo(data);
        assertThat(shard.crc32c()).isEqualTo(crc(data));
    }

    @Test
    void emptyShardIsAllowed() throws Exception {
        write(KEY, new byte[0]);
        assertThat(store.read(KEY).orElseThrow().payload()).isEmpty();
    }

    @Test
    void missingShardReadsAsEmpty() throws Exception {
        assertThat(store.read(KEY)).isEmpty();
    }

    @Test
    void rewritingTheSameShardIsIdempotent() throws Exception {
        byte[] data = randomBytes(1000);
        write(KEY, data);
        write(KEY, data); // a retried write must succeed, not fail with "already exists"
        assertThat(store.read(KEY).orElseThrow().payload()).isEqualTo(data);
    }

    // ---- rejected writes leave nothing behind -----------------------------

    @Test
    void rejectsWriteWhenChecksumDoesNotMatch() throws Exception {
        byte[] data = randomBytes(1000);
        try (ShardWriter w = store.beginWrite(KEY, data.length, crc(data) ^ 1)) {
            w.append(ByteBuffer.wrap(data));
            assertThatThrownBy(w::commit).isInstanceOf(ShardWriteRejectedException.class)
                    .hasMessageContaining("checksum");
        }
        assertNothingStored();
    }

    @Test
    void rejectsWriteWithFewerBytesThanDeclared() throws Exception {
        byte[] data = randomBytes(1000);
        try (ShardWriter w = store.beginWrite(KEY, data.length + 1, crc(data))) {
            w.append(ByteBuffer.wrap(data));
            assertThatThrownBy(w::commit).isInstanceOf(ShardWriteRejectedException.class);
        }
        assertNothingStored();
    }

    @Test
    void rejectsWriteWithMoreBytesThanDeclared() throws Exception {
        byte[] data = randomBytes(1000);
        try (ShardWriter w = store.beginWrite(KEY, data.length - 1, crc(data))) {
            assertThatThrownBy(() -> w.append(ByteBuffer.wrap(data)))
                    .isInstanceOf(ShardWriteRejectedException.class);
        }
        assertNothingStored();
    }

    @Test
    void abandonedWriteLeavesNothing() throws Exception {
        // e.g. the client disconnects halfway through the stream
        byte[] data = randomBytes(1000);
        try (ShardWriter w = store.beginWrite(KEY, data.length, crc(data))) {
            w.append(ByteBuffer.wrap(data, 0, 500));
        }
        assertNothingStored();
    }

    @Test
    void abandonedRewriteKeepsThePreviousVersion() throws Exception {
        byte[] original = randomBytes(1000);
        write(KEY, original);
        try (ShardWriter w = store.beginWrite(KEY, 1000, 0)) {
            w.append(ByteBuffer.wrap(randomBytes(10)));
        }
        assertThat(store.read(KEY).orElseThrow().payload()).isEqualTo(original);
    }

    @Test
    void leftoverTempFilesFromACrashAreRemovedOnStartup() throws Exception {
        // Simulate a crash mid-write: a temp file exists but was never renamed.
        Path temp = store.pathFor(KEY).resolveSibling(KEY.fileName() + ".deadbeef.tmp");
        Files.write(temp, randomBytes(100));

        ShardStore reopened = ShardStore.open(dataDir);

        assertThat(temp).doesNotExist();
        assertThat(reopened.read(KEY)).isEmpty();
    }

    // ---- corruption is detected, never served -----------------------------

    @Test
    void detectsFlippedPayloadByte() throws Exception {
        write(KEY, randomBytes(1000));
        flipByteAt(store.pathFor(KEY), Files.size(store.pathFor(KEY)) - 10);
        assertCorrupt("payload checksum");
    }

    @Test
    void detectsFlippedHeaderByte() throws Exception {
        write(KEY, randomBytes(1000));
        // Byte 17 is inside stripeIndex for a 6-char versionId; the header CRC must catch it.
        flipByteAt(store.pathFor(KEY), 17);
        assertCorrupt("header checksum");
    }

    @Test
    void detectsBadMagic() throws Exception {
        write(KEY, randomBytes(1000));
        flipByteAt(store.pathFor(KEY), 0);
        assertCorrupt("magic");
    }

    @Test
    void detectsTruncatedFile() throws Exception {
        write(KEY, randomBytes(1000));
        try (FileChannel ch = FileChannel.open(store.pathFor(KEY), StandardOpenOption.WRITE)) {
            ch.truncate(ch.size() - 1);
        }
        assertCorrupt("bytes, expected");
    }

    @Test
    void detectsFileTruncatedInsideHeader() throws Exception {
        write(KEY, randomBytes(1000));
        try (FileChannel ch = FileChannel.open(store.pathFor(KEY), StandardOpenOption.WRITE)) {
            ch.truncate(5);
        }
        assertCorrupt("truncated");
    }

    @Test
    void detectsFileStoredUnderTheWrongName() throws Exception {
        ShardKey other = new ShardKey(KEY.versionId(), KEY.stripeIndex(), KEY.shardIndex() - 1);
        write(other, randomBytes(1000));
        Files.copy(store.pathFor(other), store.pathFor(KEY)); // same bucket: same versionId
        assertCorrupt("contains shard");
    }

    // ---- input validation -------------------------------------------------

    @Test
    void shardKeyRejectsPathTraversal() {
        assertThatThrownBy(() -> new ShardKey("../../etc", 0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ShardKey("a/b", 0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ShardKey("a_b", 0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ShardKey("", 0, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAbsurdDeclaredLength() {
        assertThatThrownBy(() -> store.beginWrite(KEY, ShardHeader.MAX_PAYLOAD_BYTES + 1, 0))
                .isInstanceOf(ShardWriteRejectedException.class);
    }

    // ---- helpers ----------------------------------------------------------

    /** Writes in 64 KB chunks, like the gRPC stream will. */
    private void write(ShardKey key, byte[] data) throws Exception {
        try (ShardWriter w = store.beginWrite(key, data.length, crc(data))) {
            for (int off = 0; off < data.length; off += 64 * 1024) {
                w.append(ByteBuffer.wrap(data, off, Math.min(64 * 1024, data.length - off)));
            }
            w.commit();
        }
    }

    private void assertNothingStored() throws Exception {
        assertThat(store.read(KEY)).isEmpty();
        try (var files = Files.list(store.pathFor(KEY).getParent())) {
            assertThat(files).as("no temp files left behind").isEmpty();
        }
    }

    private void assertCorrupt(String messageFragment) {
        assertThatThrownBy(() -> store.read(KEY))
                .isInstanceOf(CorruptShardException.class)
                .hasMessageContaining(messageFragment);
    }

    private static void flipByteAt(Path file, long position) throws IOException {
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            ByteBuffer b = ByteBuffer.allocate(1);
            ch.read(b, position);
            b.put(0, (byte) (b.get(0) ^ 0x01));
            b.rewind();
            ch.write(b, position);
        }
    }

    private static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        new Random(42).nextBytes(b);
        return b;
    }

    private static int crc(byte[] data) {
        CRC32C c = new CRC32C();
        c.update(data);
        return (int) c.getValue();
    }
}
