package dev.distrisys.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32C;

/**
 * Fixed layout at the start of every shard file. All integers are big-endian.
 *
 * <pre>
 * offset  size  field
 * 0       4     magic          "DSH1"
 * 4       2     formatVersion  1
 * 6       2     idLength       n (length of versionId in bytes)
 * 8       n     versionId      ASCII
 * 8+n     4     stripeIndex
 * 12+n    4     shardIndex
 * 16+n    8     payloadLength
 * 24+n    4     payloadCrc32c
 * 28+n    4     headerCrc32c   CRC32C of bytes [0, 28+n)
 * 32+n    ...   payload
 * </pre>
 *
 * The header has its own checksum because a flipped bit in payloadLength would otherwise make
 * us misread the whole file, and the payload checksum alone can't tell us that.
 */
record ShardHeader(ShardKey key, long payloadLength, int payloadCrc32c) {

    static final int MAGIC = 0x44534831; // "DSH1"
    static final short FORMAT_VERSION = 1;
    /** Upper bound so a corrupt length field can't make us allocate gigabytes. */
    static final long MAX_PAYLOAD_BYTES = 64L * 1024 * 1024;

    private static final int PREFIX_SIZE = 8;  // magic + formatVersion + idLength
    private static final int FIXED_SIZE = 32;  // every field except versionId

    int encodedSize() {
        return FIXED_SIZE + key.versionId().length();
    }

    ByteBuffer encode() {
        byte[] id = key.versionId().getBytes(StandardCharsets.US_ASCII);
        ByteBuffer buf = ByteBuffer.allocate(encodedSize());
        buf.putInt(MAGIC)
                .putShort(FORMAT_VERSION)
                .putShort((short) id.length)
                .put(id)
                .putInt(key.stripeIndex())
                .putInt(key.shardIndex())
                .putLong(payloadLength)
                .putInt(payloadCrc32c);
        buf.putInt(crc32c(buf.array(), buf.position()));
        return buf.flip();
    }

    /** Reads and fully validates the header at the start of {@code ch}. */
    static ShardHeader read(FileChannel ch) throws IOException, CorruptShardException {
        ByteBuffer prefix = ByteBuffer.allocate(PREFIX_SIZE);
        readFully(ch, prefix, 0);
        int magic = prefix.getInt(0);
        short version = prefix.getShort(4);
        int idLength = prefix.getShort(6);
        if (magic != MAGIC) {
            throw new CorruptShardException("bad magic: 0x" + Integer.toHexString(magic));
        }
        if (version != FORMAT_VERSION) {
            throw new CorruptShardException("unsupported format version: " + version);
        }
        if (idLength < 1 || idLength > 128) {
            throw new CorruptShardException("bad versionId length: " + idLength);
        }

        ByteBuffer full = ByteBuffer.allocate(FIXED_SIZE + idLength);
        readFully(ch, full, 0);
        int storedHeaderCrc = full.getInt(full.capacity() - 4);
        if (crc32c(full.array(), full.capacity() - 4) != storedHeaderCrc) {
            throw new CorruptShardException("header checksum mismatch");
        }

        full.position(PREFIX_SIZE);
        byte[] id = new byte[idLength];
        full.get(id);
        int stripeIndex = full.getInt();
        int shardIndex = full.getInt();
        long payloadLength = full.getLong();
        int payloadCrc = full.getInt();
        if (payloadLength < 0 || payloadLength > MAX_PAYLOAD_BYTES) {
            throw new CorruptShardException("bad payload length: " + payloadLength);
        }
        try {
            ShardKey key = new ShardKey(new String(id, StandardCharsets.US_ASCII), stripeIndex, shardIndex);
            return new ShardHeader(key, payloadLength, payloadCrc);
        } catch (IllegalArgumentException e) {
            throw new CorruptShardException("bad shard id in header: " + e.getMessage());
        }
    }

    /** Reads exactly {@code buf.remaining()} bytes at {@code position}; a short file means corruption. */
    static void readFully(FileChannel ch, ByteBuffer buf, long position) throws IOException, CorruptShardException {
        long pos = position;
        while (buf.hasRemaining()) {
            int n = ch.read(buf, pos);
            if (n < 0) {
                throw new CorruptShardException("file truncated at byte " + pos);
            }
            pos += n;
        }
    }

    private static int crc32c(byte[] bytes, int length) {
        CRC32C crc = new CRC32C();
        crc.update(bytes, 0, length);
        return (int) crc.getValue();
    }
}
