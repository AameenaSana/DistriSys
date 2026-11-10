package dev.distrisys.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.zip.CRC32C;

/**
 * Streams one shard into a temp file and publishes it under its real name only on {@link #commit()}.
 * If the writer is closed without committing (client disconnected, validation failed, crash),
 * the real name is never touched, so a reader can never see a partially written shard.
 */
public final class ShardWriter implements AutoCloseable {

    private final ShardHeader header;
    private final Path tempPath;
    private final Path finalPath;
    private final FileChannel channel;
    private final CRC32C crc = new CRC32C();
    private long written;
    private boolean committed;

    ShardWriter(ShardHeader header, Path tempPath, Path finalPath) throws IOException {
        this.header = header;
        this.tempPath = tempPath;
        this.finalPath = finalPath;
        this.channel = FileChannel.open(tempPath, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        writeFully(header.encode());
    }

    public void append(ByteBuffer chunk) throws IOException, ShardWriteRejectedException {
        if (written + chunk.remaining() > header.payloadLength()) {
            throw new ShardWriteRejectedException("shard " + header.key() + ": received more than the declared "
                    + header.payloadLength() + " bytes");
        }
        written += chunk.remaining();
        crc.update(chunk.duplicate());
        writeFully(chunk);
    }

    /** Verifies what we received, then makes it durable and visible: fsync file, rename, fsync directory. */
    public void commit() throws IOException, ShardWriteRejectedException {
        if (written != header.payloadLength()) {
            throw new ShardWriteRejectedException("shard " + header.key() + ": declared "
                    + header.payloadLength() + " bytes but received " + written);
        }
        int actualCrc = (int) crc.getValue();
        if (actualCrc != header.payloadCrc32c()) {
            throw new ShardWriteRejectedException("shard " + header.key() + ": checksum mismatch (declared "
                    + Integer.toHexString(header.payloadCrc32c()) + ", received " + Integer.toHexString(actualCrc) + ")");
        }
        channel.force(true);
        channel.close();
        // Atomic replace: a retry of the same shard simply swaps in an identical file.
        Files.move(tempPath, finalPath, StandardCopyOption.ATOMIC_MOVE);
        Durability.fsyncDirectory(finalPath.getParent());
        committed = true;
    }

    @Override
    public void close() throws IOException {
        if (!committed) {
            channel.close();
            Files.deleteIfExists(tempPath);
        }
    }

    private void writeFully(ByteBuffer buf) throws IOException {
        while (buf.hasRemaining()) {
            channel.write(buf);
        }
    }
}
