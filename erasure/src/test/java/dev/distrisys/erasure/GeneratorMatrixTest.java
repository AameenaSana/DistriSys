package dev.distrisys.erasure;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

class GeneratorMatrixTest {

    private static final int[][] IDENTITY = {
            {1, 0, 0, 0},
            {0, 1, 0, 0},
            {0, 0, 1, 0},
            {0, 0, 0, 1}
    };

    @Test
    void firstFourRowsPreserveData() {
        int[][] generator = GeneratorMatrix.create();
        assertEquals(6, generator.length);
        assertArrayEquals(IDENTITY, Arrays.copyOf(generator, 4));
    }

    @Test
    void everyPairOfMissingShardsLeavesAnInvertibleMatrix() {
        int[][] generator = GeneratorMatrix.create();
        for (int first = 0; first < generator.length; first++) {
            for (int second = first + 1; second < generator.length; second++) {
                int[][] surviving = new int[4][];
                int next = 0;
                for (int row = 0; row < generator.length; row++) {
                    if (row != first && row != second) {
                        surviving[next++] = generator[row];
                    }
                }
                assertArrayEquals(IDENTITY, GfMatrix.multiply(surviving, GfMatrix.invert(surviving)),
                        "missing shards " + first + " and " + second);
            }
        }
    }
}
