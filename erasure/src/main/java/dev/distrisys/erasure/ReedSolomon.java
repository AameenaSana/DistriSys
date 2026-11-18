package dev.distrisys.erasure;

import java.util.Arrays;

/** Systematic Reed–Solomon codec for one stripe of four data shards and two parity shards. */
public final class ReedSolomon {

    private static final int DATA_SHARDS = GeneratorMatrix.DATA_SHARDS;
    private static final int TOTAL_SHARDS = GeneratorMatrix.TOTAL_SHARDS;
    private static final int[][] GENERATOR = GeneratorMatrix.create();

    private ReedSolomon() {
    }

    /**
     * Returns six shards in generator row order. The first four share the input arrays.
     * All four input shards must have the same length.
     */
    public static byte[][] encode(byte[][] data) {
        int length = shardLength(data, DATA_SHARDS);
        for (byte[] shard : data) {
            if (shard == null) {
                throw new IllegalArgumentException("encoding requires four data shards");
            }
        }
        byte[][] shards = Arrays.copyOf(data, TOTAL_SHARDS);
        fillParity(shards, length);
        return shards;
    }

    /**
     * Returns a complete stripe without changing the input. Use null for missing shards and
     * verify surviving shards' checksums before calling. Present arrays are shared with the result.
     */
    public static byte[][] reconstruct(byte[][] shards) {
        int length = shardLength(shards, TOTAL_SHARDS);
        byte[][] survivors = new byte[DATA_SHARDS][];
        int[][] rows = new int[DATA_SHARDS][];
        int count = 0;
        for (int i = 0; i < shards.length && count < DATA_SHARDS; i++) {
            if (shards[i] != null) {
                survivors[count] = shards[i];
                rows[count] = GENERATOR[i];
                count++;
            }
        }
        if (count < DATA_SHARDS) {
            throw new IllegalArgumentException("reconstruction requires four surviving shards");
        }

        byte[][] restored = shards.clone();
        int[][] inverse = null;
        for (int i = 0; i < DATA_SHARDS; i++) {
            if (restored[i] == null) {
                if (inverse == null) {
                    inverse = GfMatrix.invert(rows);
                }
                restored[i] = combine(inverse[i], survivors, length);
            }
        }
        fillParity(restored, length);
        return restored;
    }

    private static void fillParity(byte[][] shards, int length) {
        for (int i = DATA_SHARDS; i < TOTAL_SHARDS; i++) {
            if (shards[i] == null) {
                shards[i] = combine(GENERATOR[i], shards, length);
            }
        }
    }

    private static byte[] combine(int[] coefficients, byte[][] inputs, int length) {
        byte[] output = new byte[length];
        for (int i = 0; i < coefficients.length; i++) {
            byte[] input = inputs[i];
            for (int offset = 0; offset < length; offset++) {
                output[offset] ^= (byte) GF256.mul(coefficients[i], input[offset] & 0xFF);
            }
        }
        return output;
    }

    private static int shardLength(byte[][] shards, int expectedCount) {
        if (shards == null || shards.length != expectedCount) {
            throw new IllegalArgumentException("expected " + expectedCount + " shard slots");
        }
        int length = -1;
        for (byte[] shard : shards) {
            if (shard == null) {
                continue;
            }
            if (length >= 0 && shard.length != length) {
                throw new IllegalArgumentException("shard lengths must match");
            }
            length = shard.length;
        }
        return length;
    }
}
