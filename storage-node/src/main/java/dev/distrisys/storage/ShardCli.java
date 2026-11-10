package dev.distrisys.storage;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32C;

import com.google.protobuf.ByteString;

import dev.distrisys.proto.storage.ReadShardRequest;
import dev.distrisys.proto.storage.ReadShardResponse;
import dev.distrisys.proto.storage.ShardId;
import dev.distrisys.proto.storage.StorageNodeGrpc;
import dev.distrisys.proto.storage.WriteShardHeader;
import dev.distrisys.proto.storage.WriteShardRequest;
import dev.distrisys.proto.storage.WriteShardResponse;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;

/**
 * Debug tool for talking to one storage node directly.
 * <pre>
 * shard-cli HOST:PORT put VERSION STRIPE SHARD FILE
 * shard-cli HOST:PORT get VERSION STRIPE SHARD FILE
 * </pre>
 */
public final class ShardCli {

    private ShardCli() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 6 || !(args[1].equals("put") || args[1].equals("get"))) {
            System.err.println("usage: shard-cli HOST:PORT (put|get) VERSION STRIPE SHARD FILE");
            System.exit(2);
        }
        ShardId id = ShardId.newBuilder()
                .setVersionId(args[2])
                .setStripeIndex(Integer.parseInt(args[3]))
                .setShardIndex(Integer.parseInt(args[4]))
                .build();
        Path file = Path.of(args[5]);

        ManagedChannel channel = Grpc.newChannelBuilder(args[0], InsecureChannelCredentials.create()).build();
        try {
            if (args[1].equals("put")) {
                put(channel, id, Files.readAllBytes(file));
            } else {
                get(channel, id, file);
            }
        } catch (StatusRuntimeException e) {
            System.err.println("FAILED: " + e.getStatus().getCode() + " - " + e.getStatus().getDescription());
            System.exit(1);
        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private static void put(ManagedChannel channel, ShardId id, byte[] data) throws Exception {
        CompletableFuture<WriteShardResponse> result = new CompletableFuture<>();
        StreamObserver<WriteShardRequest> requests = StorageNodeGrpc.newStub(channel).writeShard(new StreamObserver<>() {
            @Override
            public void onNext(WriteShardResponse r) {
                result.complete(r);
            }

            @Override
            public void onError(Throwable t) {
                result.completeExceptionally(t);
            }

            @Override
            public void onCompleted() {
            }
        });
        requests.onNext(WriteShardRequest.newBuilder()
                .setHeader(WriteShardHeader.newBuilder().setShardId(id).setLength(data.length).setCrc32C(crc(data)))
                .build());
        for (int off = 0; off < data.length; off += StorageNodeService.CHUNK_SIZE) {
            int len = Math.min(StorageNodeService.CHUNK_SIZE, data.length - off);
            requests.onNext(WriteShardRequest.newBuilder().setChunk(ByteString.copyFrom(data, off, len)).build());
        }
        requests.onCompleted();
        try {
            WriteShardResponse ack = result.get(30, TimeUnit.SECONDS);
            System.out.printf("OK stored %d bytes, crc32c=%08x%n", ack.getLength(), ack.getCrc32C());
        } catch (java.util.concurrent.ExecutionException e) {
            throw (Exception) e.getCause();
        }
    }

    private static void get(ManagedChannel channel, ShardId id, Path file) throws Exception {
        Iterator<ReadShardResponse> it = StorageNodeGrpc.newBlockingStub(channel)
                .withDeadlineAfter(30, TimeUnit.SECONDS)
                .readShard(ReadShardRequest.newBuilder().setShardId(id).build());
        ReadShardResponse header = it.next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (it.hasNext()) {
            out.writeBytes(it.next().getChunk().toByteArray());
        }
        byte[] data = out.toByteArray();
        // Verify end-to-end: the node checked its disk, we check what arrived over the network.
        if (crc(data) != header.getHeader().getCrc32C() || data.length != header.getHeader().getLength()) {
            System.err.println("FAILED: received data does not match the node's header");
            System.exit(1);
        }
        Files.write(file, data);
        System.out.printf("OK read %d bytes, crc32c=%08x -> %s%n", data.length, crc(data), file);
    }

    private static int crc(byte[] data) {
        CRC32C c = new CRC32C();
        c.update(data);
        return (int) c.getValue();
    }
}
