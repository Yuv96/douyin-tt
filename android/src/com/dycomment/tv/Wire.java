package com.dycomment.tv;

import java.util.*;

/** Minimal bounded protobuf wire support; only explicitly selected fields are interpreted. */
final class Wire {
    final Map<Integer, List<Object>> fields = new HashMap<>();

    Wire(byte[] bytes) throws Exception {
        if (bytes.length > 2 * 1024 * 1024) throw new Exception("响应过大");
        int[] at = {0};
        int count = 0;
        while (at[0] < bytes.length) {
            if (++count > 30000) throw new Exception("响应字段过多");
            long tag = read(bytes, at);
            int id = (int) (tag >>> 3), type = (int) (tag & 7);
            if (id <= 0) throw new Exception("响应格式异常");
            Object value;
            if (type == 0) value = read(bytes, at);
            else if (type == 2 || type == 1 || type == 5) {
                long size = type == 2 ? read(bytes, at) : type == 1 ? 8 : 4;
                if (size < 0 || size > bytes.length - at[0]) throw new Exception("响应截断");
                value = Arrays.copyOfRange(bytes, at[0], at[0] + (int) size);
                at[0] += (int) size;
            } else throw new Exception("响应编码不支持");
            List<Object> list = fields.get(id);
            if (list == null) {
                list = new ArrayList<>();
                fields.put(id, list);
            }
            list.add(value);
        }
    }

    static long read(byte[] b, int[] p) throws Exception {
        long value = 0;
        for (int shift = 0; shift < 64; shift += 7) {
            if (p[0] >= b.length) throw new Exception("响应截断");
            int v = b[p[0]++] & 255;
            value |= (long) (v & 127) << shift;
            if (v < 128) return value;
        }
        throw new Exception("响应数字异常");
    }

    List<Object> all(int id) {
        List<Object> r = fields.get(id);
        return r == null ? Collections.emptyList() : r;
    }

    Object first(int id) {
        List<Object> r = all(id);
        return r.isEmpty() ? null : r.get(0);
    }

    long number(int id, long fallback) {
        Object v = first(id);
        return v instanceof Long ? (Long) v : fallback;
    }

    byte[] bytes(int id) {
        Object v = first(id);
        return v instanceof byte[] ? (byte[]) v : new byte[0];
    }

    String text(int id) throws Exception {
        return new String(bytes(id), "UTF-8");
    }

    Wire child(int id) throws Exception {
        return new Wire(bytes(id));
    }
}
