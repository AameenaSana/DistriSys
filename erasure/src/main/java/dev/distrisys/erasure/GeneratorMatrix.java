package dev.distrisys.erasure;

import java.util.Arrays;

/** Generator for four data shards followed by two parity shards. */
final class GeneratorMatrix {

    static final int DATA_SHARDS = 4;
    static final int TOTAL_SHARDS = 6;

    private GeneratorMatrix() {
    }

    static int[][] create() {
        int[][] vandermonde = new int[TOTAL_SHARDS][DATA_SHARDS];
        for (int row = 0; row < TOTAL_SHARDS; row++) {
            for (int col = 0; col < DATA_SHARDS; col++) {
                vandermonde[row][col] = GF256.pow(row, col);
            }
        }
        // Right-multiply by the inverse of the top block to turn it into the identity.
        int[][] top = Arrays.copyOf(vandermonde, DATA_SHARDS);
        return GfMatrix.multiply(vandermonde, GfMatrix.invert(top));
    }
}
