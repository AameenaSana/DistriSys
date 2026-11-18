package dev.distrisys.erasure;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class GfMatrixTest {

    private static final int[][] IDENTITY = {{1, 0}, {0, 1}};

    @Test
    void identityPreservesMatrix() {
        int[][] matrix = {{1, 2, 3}, {4, 5, 6}};
        assertArrayEquals(matrix, GfMatrix.multiply(IDENTITY, matrix));
        assertArrayEquals(IDENTITY, GfMatrix.invert(IDENTITY));
    }

    @Test
    void inversionSwapsAndScalesPivotRowsWithoutChangingInput() {
        int[][] matrix = {{0, 1}, {2, 1}};
        int[][] inverse = GfMatrix.invert(matrix);

        assertArrayEquals(new int[][] {{0x8E, 0x8E}, {1, 0}}, inverse);
        assertArrayEquals(new int[][] {{0, 1}, {2, 1}}, matrix);
        assertArrayEquals(IDENTITY, GfMatrix.multiply(matrix, inverse));
        assertArrayEquals(IDENTITY, GfMatrix.multiply(inverse, matrix));
    }

    @Test
    void duplicateRowsMakeMatrixSingular() {
        int[][] matrix = {{1, 2}, {1, 2}};
        assertThrows(IllegalArgumentException.class, () -> GfMatrix.invert(matrix));
    }
}
