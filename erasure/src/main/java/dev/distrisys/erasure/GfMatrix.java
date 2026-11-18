package dev.distrisys.erasure;

/** Matrix operations over GF(256). Internal callers supply rectangular arrays of field elements. */
final class GfMatrix {

    private GfMatrix() {
    }

    static int[][] multiply(int[][] left, int[][] right) {
        if (left[0].length != right.length) {
            throw new IllegalArgumentException("incompatible matrix dimensions");
        }
        int[][] product = new int[left.length][right[0].length];
        for (int row = 0; row < product.length; row++) {
            for (int col = 0; col < product[row].length; col++) {
                for (int k = 0; k < right.length; k++) {
                    product[row][col] = GF256.add(product[row][col], GF256.mul(left[row][k], right[k][col]));
                }
            }
        }
        return product;
    }

    static int[][] invert(int[][] matrix) {
        int size = matrix.length;
        if (size == 0 || matrix[0].length != size) {
            throw new IllegalArgumentException("expected a nonempty square matrix");
        }

        // Work on [matrix | identity] so elimination never changes the input.
        int[][] augmented = new int[size][2 * size];
        for (int row = 0; row < size; row++) {
            System.arraycopy(matrix[row], 0, augmented[row], 0, size);
            augmented[row][size + row] = 1;
        }

        for (int col = 0; col < size; col++) {
            int pivot = col;
            while (pivot < size && augmented[pivot][col] == 0) {
                pivot++;
            }
            if (pivot == size) {
                throw new IllegalArgumentException("singular matrix");
            }

            int[] pivotRow = augmented[pivot];
            augmented[pivot] = augmented[col];
            augmented[col] = pivotRow;

            int inversePivot = GF256.inv(pivotRow[col]);
            for (int entry = 0; entry < pivotRow.length; entry++) {
                pivotRow[entry] = GF256.mul(pivotRow[entry], inversePivot);
            }
            for (int row = 0; row < size; row++) {
                int factor = augmented[row][col];
                if (row == col || factor == 0) {
                    continue;
                }
                for (int entry = 0; entry < pivotRow.length; entry++) {
                    augmented[row][entry] = GF256.sub(augmented[row][entry], GF256.mul(factor, pivotRow[entry]));
                }
            }
        }

        int[][] inverse = new int[size][size];
        for (int row = 0; row < size; row++) {
            System.arraycopy(augmented[row], size, inverse[row], 0, size);
        }
        return inverse;
    }
}
