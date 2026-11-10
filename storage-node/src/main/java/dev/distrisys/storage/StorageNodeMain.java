package dev.distrisys.storage;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.grpc.Grpc;
import io.grpc.InsecureServerCredentials;
import io.grpc.Server;

/**
 * Entry point for a storage node. Configured entirely through environment variables,
 * which is what Docker Compose (and later Testcontainers) can set most easily.
 */
public final class StorageNodeMain {

    private static final Logger log = LoggerFactory.getLogger(StorageNodeMain.class);
    private static final long SHUTDOWN_GRACE_SECONDS = 10;

    private StorageNodeMain() {
    }

    public static void main(String[] args) throws Exception {
        String nodeId = env("NODE_ID", "storage-local");
        Path dataDir = Path.of(env("DATA_DIR", "data"));
        int port = Integer.parseInt(env("PORT", "9090"));

        ShardStore store = ShardStore.open(dataDir);
        Server server = Grpc.newServerBuilderForPort(port, InsecureServerCredentials.create())
                .addService(new StorageNodeService(store))
                .build()
                .start();
        log.info("Storage node {} listening on port {}, data dir {}", nodeId, port, dataDir.toAbsolutePath());

        // Runs on SIGTERM (docker stop). Not on SIGKILL (docker kill), which is fine: correctness never
        // depends on a clean shutdown, because unacknowledged writes are just temp files cleaned up at startup.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> shutdown(server), "shutdown"));
        server.awaitTermination();
    }

    private static void shutdown(Server server) {
        log.info("Shutting down: refusing new calls, waiting up to {}s for in-flight ones", SHUTDOWN_GRACE_SECONDS);
        server.shutdown();
        try {
            if (!server.awaitTermination(SHUTDOWN_GRACE_SECONDS, TimeUnit.SECONDS)) {
                log.warn("In-flight calls did not finish in time; forcing shutdown");
                server.shutdownNow();
            }
        } catch (InterruptedException e) {
            server.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("Stopped");
    }

    private static String env(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
