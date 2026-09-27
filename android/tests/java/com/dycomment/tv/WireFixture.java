package com.dycomment.tv;

import java.io.ByteArrayOutputStream;

/** Test-only protobuf encoder for synthetic live-message parser fixtures. */
final class WireFixture {
    private WireFixture() {}

    static final class Out {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();

        void raw(long n) {
            while ((n & ~127L) != 0) {
                out.write(((int) n & 127) | 128);
                n >>>= 7;
            }
            out.write((int) n);
        }

        Out number(int id, long n) {
            raw(id * 8L);
            raw(n);
            return this;
        }

        Out bytes(int id, byte[] b) {
            raw(id * 8L + 2);
            raw(b.length);
            out.write(b, 0, b.length);
            return this;
        }

        Out text(int id, String s) throws Exception {
            return bytes(id, s.getBytes("UTF-8"));
        }

        byte[] done() {
            return out.toByteArray();
        }
    }
}
