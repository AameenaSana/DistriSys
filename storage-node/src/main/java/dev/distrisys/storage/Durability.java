package dev.distrisys.storage;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** The one place that knows how to make a directory change (create, rename, delete) durable. */
final class Durability {

    private static final boolean WINDOWS = System.getProperty("os.name").toLowerCase().contains("win");

    private Durability() {
    }

    /**
     * Persists the directory's entries (file names), so a completed rename survives a crash.
     * On Linux, fsyncing the file alone does not guarantee this.
     * Windows can't open a directory as a channel, and NTFS journals metadata itself,
     * so this is a no-op there. Production nodes run on Linux, where this matters.
     */
    static void fsyncDirectory(Path dir) throws IOException {
        if (WINDOWS) {
            return;
        }
        try (FileChannel ch = FileChannel.open(dir, StandardOpenOption.READ)) {
            ch.force(true);
        }
    }
}
