package dev.distrisys.erasure;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Random;

import org.junit.jupiter.api.Test;

class ReedSolomonTest {

    @Test
    void encodesAConstantPolynomial() {
        byte[] shard = {0, 1, (byte) 0x80, (byte) 0xFF};
        byte[][] data = {shard, shard, shard, shard};
        assertArrayEquals(new byte[][] {shard, shard, shard, shard, shard, shard}, ReedSolomon.encode(data));
    }

    @Test
    void reconstructsEveryPairOfMissingShards() {
        byte[][] encoded = ReedSolomon.encode(randomData(new Random(42), 257));
        for (int first = 0; first < encoded.length; first++) {
            for (int second = first + 1; second < encoded.length; second++) {
                byte[][] damaged = encoded.clone();
                damaged[first] = null;
                damaged[second] = null;
                assertArrayEquals(encoded, ReedSolomon.reconstruct(damaged),
                        "missing shards " + first + " and " + second);
                assertNull(damaged[first]);
                assertNull(damaged[second]);
            }
        }
    }

    @Test
    void roundTripsRandomDataAndErasures() {
        Random random = new Random(2026);
        for (int trial = 0; trial < 100; trial++) {
            byte[][] encoded = ReedSolomon.encode(randomData(random, random.nextInt(1024)));
            byte[][] damaged = encoded.clone();
            int missing = random.nextInt(3);
            while (missing > 0) {
                int index = random.nextInt(damaged.length);
                if (damaged[index] != null) {
                    damaged[index] = null;
                    missing--;
                }
            }
            assertArrayEquals(encoded, ReedSolomon.reconstruct(damaged), "trial " + trial);
        }
    }

    @Test
    void reconstructsEmptyShards() {
        byte[][] encoded = ReedSolomon.encode(new byte[4][0]);
        byte[][] damaged = encoded.clone();
        damaged[0] = null;
        damaged[5] = null;
        assertArrayEquals(encoded, ReedSolomon.reconstruct(damaged));
    }

    @Test
    void rejectsInvalidInputs() {
        assertThrows(IllegalArgumentException.class, () -> ReedSolomon.encode(new byte[3][]));
        assertThrows(IllegalArgumentException.class, () -> ReedSolomon.encode(new byte[4][]));
        byte[][] data = new byte[4][1];
        data[0] = new byte[2];
        assertThrows(IllegalArgumentException.class, () -> ReedSolomon.encode(data));

        byte[][] damaged = new byte[6][1];
        damaged[0] = new byte[2];
        assertThrows(IllegalArgumentException.class, () -> ReedSolomon.reconstruct(damaged));
        damaged[0] = damaged[1] = damaged[2] = null;
        assertThrows(IllegalArgumentException.class, () -> ReedSolomon.reconstruct(damaged));
    }

    private static byte[][] randomData(Random random, int length) {
        byte[][] data = new byte[4][length];
        for (byte[] shard : data) {
            random.nextBytes(shard);
        }
        return data;
    }
}
