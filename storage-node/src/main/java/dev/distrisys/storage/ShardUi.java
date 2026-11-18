package dev.distrisys.storage;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.distrisys.proto.storage.ShardId;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

/** Local browser bridge for a single gRPC storage node. */
public final class ShardUi {

    private ShardUi() {
    }

    public static void main(String[] args) throws IOException {
        String target = args.length > 0 ? args[0] : "127.0.0.1:9090";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 8080;
        byte[] page;
        try (var resource = ShardUi.class.getResourceAsStream("/shard-ui.html")) {
            page = resource.readAllBytes();
        }
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        ManagedChannel channel = Grpc.newChannelBuilder(target, InsecureChannelCredentials.create()).build();
        var executor = Executors.newFixedThreadPool(2);
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            try (exchange) {
                String authority = "127.0.0.1:" + server.getAddress().getPort();
                if (!authority.equals(exchange.getRequestHeaders().getFirst("Host"))) {
                    message(exchange, 403, "Open the UI using " + authority);
                } else if ("/".equals(exchange.getRequestURI().getPath()) && "GET".equals(exchange.getRequestMethod())) {
                    send(exchange, 200, "text/html; charset=utf-8", page);
                } else if (exchange.getRequestURI().getPath().startsWith("/shards/")) {
                    shard(exchange, channel);
                } else {
                    message(exchange, 404, "Page not found");
                }
            }
        });
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop(0);
            executor.shutdownNow();
            channel.shutdownNow();
        }));
        server.start();
        System.out.printf("DistriSys UI: http://127.0.0.1:%d/ (gRPC node %s)%n", server.getAddress().getPort(), target);
    }

    private static void shard(HttpExchange exchange, ManagedChannel channel) throws IOException {
        if (!"1".equals(exchange.getRequestHeaders().getFirst("X-Shard-UI"))) {
            message(exchange, 403, "Use the local shard UI");
            return;
        }
        String[] path = exchange.getRequestURI().getPath().split("/", -1);
        try {
            if (path.length != 5) {
                throw new IllegalArgumentException("Expected a version, stripe index and shard index");
            }
            ShardKey key = new ShardKey(path[2], Integer.parseInt(path[3]), Integer.parseInt(path[4]));
            ShardId id = ShardId.newBuilder().setVersionId(key.versionId()).setStripeIndex(key.stripeIndex())
                    .setShardIndex(key.shardIndex()).build();
            switch (exchange.getRequestMethod()) {
                case "PUT" -> upload(exchange, channel, id);
                case "GET" -> send(exchange, 200, "application/octet-stream", ShardCli.get(channel, id));
                default -> {
                    exchange.getResponseHeaders().set("Allow", "GET, PUT");
                    message(exchange, 405, "Use GET or PUT");
                }
            }
        } catch (IllegalArgumentException e) {
            message(exchange, 400, e.getMessage());
        } catch (StatusRuntimeException e) {
            int status = switch (e.getStatus().getCode()) {
                case NOT_FOUND -> 404;
                case INVALID_ARGUMENT -> 400;
                case DEADLINE_EXCEEDED -> 504;
                default -> 502;
            };
            message(exchange, status, "Storage node: " + e.getStatus().getCode());
        } catch (Exception e) {
            message(exchange, 502, "Shard transfer failed");
        }
    }

    private static void upload(HttpExchange exchange, ManagedChannel channel, ShardId id) throws Exception {
        int limit = (int) ShardHeader.MAX_PAYLOAD_BYTES;
        byte[] bytes = exchange.getRequestBody().readNBytes(limit + 1);
        if (bytes.length > limit) {
            message(exchange, 413, "Shards may be up to 64 MiB");
            return;
        }
        var ack = ShardCli.put(channel, id, bytes);
        message(exchange, 200, "Stored %d bytes · CRC32C %08x".formatted(ack.getLength(), ack.getCrc32C()));
    }

    private static void message(HttpExchange exchange, int status, String text) throws IOException {
        send(exchange, status, "text/plain; charset=utf-8", text.getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange exchange, int status, String type, byte[] bytes) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        exchange.getResponseBody().write(bytes);
    }
}
