package dev.distrisys.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32C;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.protobuf.ByteString;

import dev.distrisys.proto.storage.ReadShardRequest;
import dev.distrisys.proto.storage.ReadShardResponse;
import dev.distrisys.proto.storage.ShardId;
import dev.distrisys.proto.storage.StorageNodeGrpc;
import dev.distrisys.proto.storage.WriteShardHeader;
import dev.distrisys.proto.storage.WriteShardRequest;
import dev.distrisys.proto.storage.WriteShardResponse;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;

class StorageNodeServiceTest {

    private static final ShardId ID = ShardId.newBuilder().setVersionId("v-0001").setStripeIndex(2).setShardIndex(4).build();
    private static final ShardKey KEY = new ShardKey("v-0001", 2, 4);

    @TempDir
    Path dataDir;

    private ShardStore store;
    private Server server;
    private ManagedChannel channel;
    private StorageNodeGrpc.StorageNodeStub asyncStub;
    private StorageNodeGrpc.StorageNodeBlockingStub blockingStub;

    @BeforeEach
    void startServer() throws Exception {
        store = ShardStore.open(dataDir);
        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name).addService(new StorageNodeService(store)).build().start();
        channel = InProcessChannelBuilder.forName(name).build();
        asyncStub = StorageNodeGrpc.newStub(channel);
        blockingStub = StorageNodeGrpc.newBlockingStub(channel);
    }

    @AfterEach
    void stopServer() throws Exception {
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }

    // ---- happy path -------------------------------------------------------

    @Test
    void writeThenReadRoundTrips() throws Exception {
        byte[] data = randomBytes(256 * 1024 + 123); // not a multiple of the chunk size

        WriteShardResponse ack = write(data, header(ID, data.length, crc(data)));

        assertThat(ack.getLength()).isEqualTo(data.length);
        assertThat(ack.getCrc32C()).isEqualTo(crc(data));
        assertThat(read(ID)).isEqualTo(data);
    }

    @Test
    void emptyShardRoundTrips() throws Exception {
        write(new byte[0], header(ID, 0, crc(new byte[0])));
        assertThat(read(ID)).isEmpty();
    }

    @Test
    void concurrentWritesOfTheSameShardBothSucceed() throws Exception {
        // A retry racing the original attempt: both must succeed and the result must be intact.
        byte[] data = randomBytes(300 * 1024);
        CompletableFuture<WriteShardResponse> a = writeAsync(withChunks(headerMsg(ID, data), data));
        CompletableFuture<WriteShardResponse> b = writeAsync(withChunks(headerMsg(ID, data), data));
        a.get(10, TimeUnit.SECONDS);
        b.get(10, TimeUnit.SECONDS);
        assertThat(read(ID)).isEqualTo(data);
    }

    // ---- error mapping ----------------------------------------------------

    @Test
    void readOfMissingShardIsNotFound() {
        assertStatus(() -> read(ID), Status.Code.NOT_FOUND);
    }

    @Test
    void readOfCorruptShardIsDataLoss() throws Exception {
        byte[] data = randomBytes(1000);
        write(data, header(ID, data.length, crc(data)));
        flipLastByte(store.pathFor(KEY));

        assertStatus(() -> read(ID), Status.Code.DATA_LOSS);
    }

    @Test
    void writeWithWrongChecksumIsRejectedAndNotStored() {
        byte[] data = randomBytes(1000);
        assertStatus(() -> write(data, header(ID, data.length, crc(data) ^ 1)), Status.Code.INVALID_ARGUMENT);
        assertStatus(() -> read(ID), Status.Code.NOT_FOUND);
    }

    @Test
    void chunkBeforeHeaderIsRejected() {
        List<WriteShardRequest> msgs = List.of(chunkMsg(new byte[10]));
        assertStatus(() -> await(writeAsync(msgs)), Status.Code.INVALID_ARGUMENT);
    }

    @Test
    void duplicateHeaderIsRejected() {
        byte[] data = randomBytes(10);
        List<WriteShardRequest> msgs = List.of(headerMsg(ID, data), headerMsg(ID, data));
        assertStatus(() -> await(writeAsync(msgs)), Status.Code.INVALID_ARGUMENT);
    }

    @Test
    void streamWithoutHeaderIsRejected() {
        assertStatus(() -> await(writeAsync(List.of())), Status.Code.INVALID_ARGUMENT);
    }

    @Test
    void pathTraversalInVersionIdIsRejected() {
        ShardId evil = ID.toBuilder().setVersionId("../../etc").build();
        assertStatus(() -> write(new byte[1], header(evil, 1, crc(new byte[1]))), Status.Code.INVALID_ARGUMENT);
        assertStatus(() -> read(evil), Status.Code.INVALID_ARGUMENT);
    }

    @Test
    void readWithoutShardIdIsRejected() {
        assertStatus(() -> blockingStub.readShard(ReadShardRequest.getDefaultInstance()).next(),
                Status.Code.INVALID_ARGUMENT);
    }

    // ---- cancellation -----------------------------------------------------

    @Test
    void clientCancellingMidStreamLeavesNothingBehind() throws Exception {
        byte[] data = randomBytes(1000);
        CompletableFuture<WriteShardResponse> result = new CompletableFuture<>();
        StreamObserver<WriteShardRequest> requests = asyncStub.writeShard(observer(result));

        requests.onNext(headerMsg(ID, data));
        requests.onNext(chunkMsg(java.util.Arrays.copyOf(data, 500)));
        requests.onError(new RuntimeException("client crashed")); // cancels the call

        assertThatThrownBy(() -> result.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(StatusRuntimeException.class);
        assertStatus(() -> read(ID), Status.Code.NOT_FOUND);
        // Cleanup happens on the server's thread, so allow it a moment.
        awaitEmpty(store.pathFor(KEY).getParent(), Duration.ofSeconds(5));
    }

    // ---- client-side helpers ---------------------------------------------

    private WriteShardResponse write(byte[] data, WriteShardRequest header) throws Exception {
        return await(writeAsync(withChunks(header, data)));
    }

    /** Sends exactly these messages, then half-closes the stream. */
    private CompletableFuture<WriteShardResponse> writeAsync(List<WriteShardRequest> messages) {
        CompletableFuture<WriteShardResponse> result = new CompletableFuture<>();
        StreamObserver<WriteShardRequest> requests = asyncStub.writeShard(observer(result));
        messages.forEach(requests::onNext);
        requests.onCompleted();
        return result;
    }

    /** The header followed by {@code data} split into CHUNK_SIZE pieces, like the gateway will send. */
    private static List<WriteShardRequest> withChunks(WriteShardRequest header, byte[] data) {
        List<WriteShardRequest> chunks = new java.util.ArrayList<>();
        chunks.add(header);
        for (int off = 0; off < data.length; off += StorageNodeService.CHUNK_SIZE) {
            int len = Math.min(StorageNodeService.CHUNK_SIZE, data.length - off);
            chunks.add(chunkMsg(java.util.Arrays.copyOfRange(data, off, off + len)));
        }
        return chunks;
    }

    private byte[] read(ShardId id) {
        Iterator<ReadShardResponse> it = blockingStub.readShard(ReadShardRequest.newBuilder().setShardId(id).build());
        ReadShardResponse first = it.next();
        assertThat(first.hasHeader()).as("first message is the header").isTrue();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (it.hasNext()) {
            out.writeBytes(it.next().getChunk().toByteArray());
        }
        byte[] data = out.toByteArray();
        assertThat(data.length).isEqualTo(first.getHeader().getLength());
        assertThat(crc(data)).isEqualTo(first.getHeader().getCrc32C());
        return data;
    }

    private static WriteShardRequest header(ShardId id, long length, int crc) {
        return WriteShardRequest.newBuilder()
                .setHeader(WriteShardHeader.newBuilder().setShardId(id).setLength(length).setCrc32C(crc))
                .build();
    }

    private static WriteShardRequest headerMsg(ShardId id, byte[] data) {
        return header(id, data.length, crc(data));
    }

    private static WriteShardRequest chunkMsg(byte[] bytes) {
        return WriteShardRequest.newBuilder().setChunk(ByteString.copyFrom(bytes)).build();
    }

    private static <T> StreamObserver<T> observer(CompletableFuture<T> result) {
        return new StreamObserver<>() {
            private T value;

            @Override
            public void onNext(T v) {
                value = v;
            }

            @Override
            public void onError(Throwable t) {
                result.completeExceptionally(t);
            }

            @Override
            public void onCompleted() {
                result.complete(value);
            }
        };
    }

    private static <T> T await(CompletableFuture<T> f) throws Exception {
        try {
            return f.get(10, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw (Exception) e.getCause();
        }
    }

    private static void assertStatus(ThrowingCall call, Status.Code expected) {
        assertThatThrownBy(call::run)
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(e -> assertThat(((StatusRuntimeException) e).getStatus().getCode()).isEqualTo(expected));
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run() throws Exception;
    }

    // ---- misc helpers -----------------------------------------------------

    private static void awaitEmpty(Path dir, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            try (var files = Files.list(dir)) {
                if (files.findAny().isEmpty()) {
                    return;
                }
            }
            if (System.nanoTime() > deadline) {
                throw new AssertionError("directory still not empty: " + dir);
            }
            Thread.sleep(20);
        }
    }

    private static void flipLastByte(Path file) throws Exception {
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            long pos = ch.size() - 1;
            ByteBuffer b = ByteBuffer.allocate(1);
            ch.read(b, pos);
            b.put(0, (byte) (b.get(0) ^ 0x01)).rewind();
            ch.write(b, pos);
        }
    }

    private static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        new Random(7).nextBytes(b);
        return b;
    }

    private static int crc(byte[] data) {
        CRC32C c = new CRC32C();
        c.update(data);
        return (int) c.getValue();
    }
}
