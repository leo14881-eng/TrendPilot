package com.trendpilot.lpshadow;

import java.nio.charset.StandardCharsets;

/** 测试用 keccak256（以太坊用的原始 Keccak，填充 0x01），只用来核对函数选择器常量。 */
final class Keccak {

    private static final long[] RC = {
            0x0000000000000001L, 0x0000000000008082L, 0x800000000000808AL, 0x8000000080008000L, 0x000000000000808BL,
            0x0000000080000001L, 0x8000000080008081L, 0x8000000000008009L, 0x000000000000008AL, 0x0000000000000088L,
            0x0000000080008009L, 0x000000008000000AL, 0x000000008000808BL, 0x800000000000008BL, 0x8000000000008089L,
            0x8000000000008003L, 0x8000000000008002L, 0x8000000000000080L, 0x000000000000800AL, 0x800000008000000AL,
            0x8000000080008081L, 0x8000000000008080L, 0x0000000080000001L, 0x8000000080008008L};
    private static final int[] R = {0, 1, 62, 28, 27, 36, 44, 6, 55, 20, 3, 10, 43, 25, 39, 41, 45, 15, 21, 8, 18, 2, 61, 56, 14};

    private Keccak() {
    }

    static String selector(String signature) {
        byte[] h = keccak256(signature.getBytes(StandardCharsets.US_ASCII));
        return String.format("0x%02x%02x%02x%02x", h[0], h[1], h[2], h[3]);
    }

    static byte[] keccak256(byte[] msg) {
        int rate = 136;
        int padded = (msg.length / rate + 1) * rate;
        byte[] p = new byte[padded];
        System.arraycopy(msg, 0, p, 0, msg.length);
        p[msg.length] ^= 0x01;
        p[padded - 1] ^= (byte) 0x80;
        long[] s = new long[25];
        for (int off = 0; off < padded; off += rate) {
            for (int i = 0; i < rate / 8; i++) {
                long v = 0;
                for (int b = 0; b < 8; b++) v |= (p[off + i * 8 + b] & 0xFFL) << (8 * b);
                s[i] ^= v;
            }
            f(s);
        }
        byte[] out = new byte[32];
        for (int i = 0; i < 32; i++) out[i] = (byte) (s[i / 8] >>> (8 * (i % 8)));
        return out;
    }

    private static void f(long[] a) {
        long[] c = new long[5];
        long[] b = new long[25];
        for (int round = 0; round < 24; round++) {
            for (int x = 0; x < 5; x++) c[x] = a[x] ^ a[x + 5] ^ a[x + 10] ^ a[x + 15] ^ a[x + 20];
            for (int x = 0; x < 5; x++) {
                long d = c[(x + 4) % 5] ^ Long.rotateLeft(c[(x + 1) % 5], 1);
                for (int y = 0; y < 25; y += 5) a[y + x] ^= d;
            }
            for (int x = 0; x < 5; x++) {
                for (int y = 0; y < 5; y++) {
                    b[y + 5 * ((2 * x + 3 * y) % 5)] = Long.rotateLeft(a[x + 5 * y], R[x + 5 * y]);
                }
            }
            for (int x = 0; x < 5; x++) {
                for (int y = 0; y < 25; y += 5) a[y + x] = b[y + x] ^ (~b[y + (x + 1) % 5] & b[y + (x + 2) % 5]);
            }
            a[0] ^= RC[round];
        }
    }
}
