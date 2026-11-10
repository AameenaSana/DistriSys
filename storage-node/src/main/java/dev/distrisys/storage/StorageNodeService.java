package dev.distrisys.storage;

import java.io.IOException;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnsafeByteOperations;

import dev.distrisys.proto.storage.ReadShardHeader;
import dev.distrisys.proto.storage.ReadShardRequest;
import dev.distrisys.proto.storage.ReadShardResponse;
import dev.distrisys.proto.storage.ShardId;
import dev.distrisys.proto.storage.StorageNodeGrpc;
import dev.distrisys.proto.storage.WriteShardHeader;
import dev.distrisys.proto.storage.WriteShardRequest;
import dev.distrisys.proto.storage.WriteShardResponse;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;

/** Translates the StorageNode gRPC API into {@link ShardStore} calls. Holds no state of its own. */
public final class StorageNodeService extends StorageNodeGrpc.StorageNodeImplBase {

    private static final Logger log = LoggerFactory.getLogger(StorageNodeService.class);
    static final int CHUNK_SIZE = 64 * 1024;

    private final ShardStore store;

    public StorageNodeService(ShardStore store) {
        this.store = store;
    }

    @Override
    public StreamObserver<WriteShardRequest> writeShard(StreamObserver<WriteShardResponse> responses) {
        return new WriteShardCall(responses);
    }

    @Override
    public void readShard(ReadShardRequest request, StreamObserver<ReadShardResponse> responses) {
        ServerCallStreamObserver<ReadShardResponse> call = (ServerCallStreamObserver<ReadShardResponse>) responses;
        ShardKey key = null;
        try {
            key = toKey(request.hasShardId() ? request.getShardId() : null);
            Optional<StoredShard> stored = store.read(key);
            if (stored.isEmpty()) {
                responses.onError(Status.NOT_FOUND.withDescription("no shard " + key).asException());
                return;
            }
            byte[] payload = stored.get().payload();
            responses.onNext(ReadShardResponse.newBuilder()
                    .setHeader(ReadShardHeader.newBuilder()
                            .setShardId(request.getShardId())
                            .setLength(payload.length)
                            .setCrc32C(stored.get().crc32c()))
                    .build());
            for (int off = 0; off < payload.length; off += CHUNK_SIZE) {
                if (call.isCancelled()) {
                    log.debug("Read of {} cancelled by client", key);
                    return;
                }
                // Zero-copy wrap: safe because nobody mutates `payload` after this point.
                ByteString chunk = UnsafeByteOperations.unsafeWrap(payload, off, Math.min(CHUNK_SIZE, payload.length - off));
                responses.onNext(ReadShardResponse.newBuilder().setChunk(chunk).build());
            }
            responses.onCompleted();
        } catch (Exception e) {
            responses.onError(toStatus("read", key, e));
        }
    }

    /**
     * State for one WriteShard stream. Protocol: exactly one header, then zero or more chunks, then
     * half-close. gRPC never invokes these callbacks concurrently for the same call, so no locking.
     */
    private final class WriteShardCall implements StreamObserver<WriteShardRequest> {

        private final StreamObserver<WriteShardResponse> responses;
        private ShardKey key;
        private ShardWriter writer;
        private WriteShardHeader header;
        /** Once we've sent an error, the call is over; ignore anything still arriving. */
        private boolean finished;

        WriteShardCall(StreamObserver<WriteShardResponse> responses) {
            this.responses = responses;
        }

        @Override
        public void onNext(WriteShardRequest request) {
            if (finished) {
                return;
            }
            try {
                switch (request.getPayloadCase()) {
                    case HEADER -> start(request.getHeader());
                    case CHUNK -> append(request.getChunk());
                    case PAYLOAD_NOT_SET -> throw invalid("message has neither header nor chunk");
                }
            } catch (Exception e) {
                fail(e);
            }
        }

        private void start(WriteShardHeader h) throws Exception {
            if (writer != null) {
                throw invalid("duplicate header");
            }
            key = toKey(h.hasShardId() ? h.getShardId() : null);
            header = h;
            writer = store.beginWrite(key, h.getLength(), h.getCrc32C());
        }

        private void append(ByteString chunk) throws Exception {
            if (writer == null) {
                throw invalid("chunk received before header");
            }
            writer.append(chunk.asReadOnlyByteBuffer());
        }

        @Override
        public void onCompleted() {
            if (finished) {
                return;
            }
            try {
                if (writer == null) {
                    throw invalid("stream ended without a header");
                }
                writer.commit();
                writer.close();
                finished = true;
                log.debug("Stored shard {} ({} bytes)", key, header.getLength());
                responses.onNext(WriteShardResponse.newBuilder()
                        .setShardId(header.getShardId())
                        .setLength(header.getLength())
                        .setCrc32C(header.getCrc32C())
                        .build());
                responses.onCompleted();
            } catch (Exception e) {
                fail(e);
            }
        }

        /** The client cancelled or the connection broke: discard the partial shard. */
        @Override
        public void onError(Throwable t) {
            log.info("Write of {} aborted by client: {}", key, Status.fromThrowable(t).getCode());
            finished = true;
            abort();
        }

        private void fail(Exception e) {
            finished = true;
            abort();
            responses.onError(toStatus("write", key, e));
        }

        private void abort() {
            if (writer == null) {
                return;
            }
            try {
                writer.close(); // deletes the temp file unless already committed
            } catch (IOException e) {
                log.warn("Could not clean up partial write of {}", key, e);
            }
        }
    }

    // ---- helpers ----------------------------------------------------------

    private static ShardKey toKey(ShardId id) throws StatusException {
        if (id == null) {
            throw invalid("missing shard_id");
        }
        try {
            return new ShardKey(id.getVersionId(), id.getStripeIndex(), id.getShardIndex());
        } catch (IllegalArgumentException e) {
            throw invalid(e.getMessage());
        }
    }

    private static StatusException invalid(String message) {
        return Status.INVALID_ARGUMENT.withDescription(message).asException();
    }

    /** The single place that decides which gRPC status a failure becomes (see the table in the docs). */
    private static StatusException toStatus(String op, ShardKey key, Exception e) {
        return switch (e) {
            case StatusException se -> se;
            case ShardWriteRejectedException r -> Status.INVALID_ARGUMENT.withDescription(r.getMessage()).asException();
            case CorruptShardException c -> {
                log.warn("Corrupt shard {}: {}", key, c.getMessage());
                yield Status.DATA_LOSS.withDescription(c.getMessage()).asException();
            }
            default -> {
                log.error("Unexpected failure during {} of {}", op, key, e);
                yield Status.INTERNAL.withDescription(op + " failed: " + e).withCause(e).asException();
            }
        };
    }
}
