package com.dycomment.tv;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Bounded, non-fragmented ISO BMFF sample-table reader; no bitrate estimation. */
public final class Mp4Window {
    private static final int MAX_MOOV = 16 * 1024 * 1024;
    private static final int MAX_BOXES = 10000, MAX_TRACKS = 32, MAX_ENTRIES = 1000000;
    private static final long MAX_SAMPLES = 2000000;

    private Mp4Window() { }

    /**
     * @param moov one complete moov box, including its 8- or 16-byte box header
     * @param totalSize complete source file length
     * @param seconds positive media decode-time horizon, after a supported edit's media start
     * @return maximum absolute sample end, exclusive, across audio and video tracks
     *
     * A prefix ending here contains all samples whose decode start precedes the horizon.
     * A single rate-1 edit with a nonnegative media start is supported, retaining decoder
     * preroll from DTS zero. Complex edits and exact presentation-time clipping are not used.
     * The caller must separately retain the supplied moov when it lives beyond the prefix.
     * Unsupported layouts and malformed/inconsistent tables throw IOException.
     */
    public static long prefixEnd(byte[] moov, long totalSize, long seconds) throws IOException {
        if (moov == null || moov.length < 8 || moov.length > MAX_MOOV
                || totalSize < moov.length || seconds <= 0) throw invalid("Invalid input bounds");
        Reader reader = new Reader(moov);
        Box root = reader.box(0, moov.length);
        if (root.type != tag("moov") || root.end != moov.length) throw invalid("Expected one complete moov box");
        List<Box> children = reader.children(root);
        long end = 0;
        int tracks = 0;
        for (Box child : children) {
            if (child.type == tag("mvex") || child.type == tag("moof")) throw invalid("Fragmented MP4 is unsupported");
            if (child.type == tag("trak")) {
                if (++tracks > MAX_TRACKS) throw invalid("Too many tracks");
                end = Math.max(end, reader.track(child, totalSize, seconds));
            }
        }
        if (end == 0) throw invalid("No audio/video sample bytes in requested window");
        return end;
    }

    private static IOException invalid(String reason) { return new IOException(reason); }

    private static int tag(String value) {
        return value.charAt(0) << 24 | value.charAt(1) << 16 | value.charAt(2) << 8 | value.charAt(3);
    }

    private static long add(long a, long b) throws IOException {
        if (a < 0 || b < 0 || a > Long.MAX_VALUE - b) throw invalid("Integer overflow");
        return a + b;
    }

    private static long multiply(long a, long b) throws IOException {
        if (a < 0 || b < 0 || (a != 0 && b > Long.MAX_VALUE / a)) throw invalid("Integer overflow");
        return a * b;
    }

    private static final class Box {
        final int type, payload, end;
        Box(int type, int payload, int end) { this.type = type; this.payload = payload; this.end = end; }
    }

    private static final class Table {
        final int start, count;
        Table(int start, int count) { this.start = start; this.count = count; }
    }

    private static final class Reader {
        final byte[] data;
        int boxes;
        long sampleBudget;
        Reader(byte[] data) { this.data = data; }

        long u32(int at) throws IOException {
            if (at < 0 || at > data.length - 4) throw invalid("Truncated integer");
            return ((long) (data[at] & 255) << 24) | ((long) (data[at + 1] & 255) << 16)
                    | ((long) (data[at + 2] & 255) << 8) | (data[at + 3] & 255);
        }

        long u64(int at) throws IOException {
            if (at < 0 || at > data.length - 8 || (data[at] & 128) != 0)
                throw invalid("Truncated or overflowing 64-bit integer");
            return u32(at) << 32 | u32(at + 4);
        }

        Box box(int at, int limit) throws IOException {
            if (++boxes > MAX_BOXES || at < 0 || limit - at < 8) throw invalid("Invalid box boundary");
            long size = u32(at);
            int header = 8;
            if (size == 1) {
                if (limit - at < 16) throw invalid("Truncated large box");
                size = u64(at + 8);
                header = 16;
            }
            if (size < header || size > limit - at) throw invalid("Invalid or open-ended box size");
            return new Box((int) u32(at + 4), at + header, at + (int) size);
        }

        List<Box> children(Box parent) throws IOException {
            List<Box> result = new ArrayList<>();
            for (int at = parent.payload; at < parent.end;) {
                Box child = box(at, parent.end);
                result.add(child);
                at = child.end;
            }
            return result;
        }

        Box one(List<Box> boxes, String name, boolean required) throws IOException {
            Box result = null;
            int type = tag(name);
            for (Box box : boxes) if (box.type == type) {
                if (result != null) throw invalid("Duplicate " + name);
                result = box;
            }
            if (result == null && required) throw invalid("Missing " + name);
            return result;
        }

        void length(Box box, long bytes) throws IOException {
            if (bytes < 0 || bytes > box.end - box.payload) throw invalid("Truncated box payload");
        }

        void full(Box box, int bytes) throws IOException {
            length(box, bytes);
            if (u32(box.payload) != 0) throw invalid("Unsupported full-box version or flags");
        }

        Table table(Box box, int stride) throws IOException {
            full(box, 8);
            long count = u32(box.payload + 4);
            if (count > MAX_ENTRIES || add(8, multiply(count, stride)) != box.end - box.payload)
                throw invalid("Invalid table length or entry limit");
            return new Table(box.payload + 8, (int) count);
        }

        long timescale(Box mdhd) throws IOException {
            length(mdhd, 4);
            long flags = u32(mdhd.payload);
            int version = (int) (flags >>> 24);
            if ((flags & 0xffffff) != 0 || (version != 0 && version != 1)) throw invalid("Unsupported mdhd");
            length(mdhd, version == 0 ? 24 : 36);
            if (version == 1) u64(mdhd.payload + 24); // Reject duration outside signed file/timestamp bounds.
            long scale = u32(mdhd.payload + (version == 0 ? 12 : 20));
            if (scale == 0) throw invalid("Zero media timescale");
            return scale;
        }

        long editStart(Box edts) throws IOException {
            if (edts == null) return 0;
            Box elst = one(children(edts), "elst", true);
            length(elst, 8);
            long flags = u32(elst.payload);
            int version = (int) (flags >>> 24);
            if ((flags & 0xffffff) != 0 || (version != 0 && version != 1)
                    || u32(elst.payload + 4) != 1
                    || elst.end - elst.payload != (version == 0 ? 20 : 28))
                throw invalid("Only a single rate-1 media edit is supported");
            long duration = version == 0 ? u32(elst.payload + 8) : u64(elst.payload + 8);
            long start = version == 0 ? u32(elst.payload + 12) : u64(elst.payload + 16);
            int rateAt = elst.payload + (version == 0 ? 16 : 24);
            if (duration == 0 || (version == 0 && start > Integer.MAX_VALUE) || u32(rateAt) != 65536)
                throw invalid("Empty, negative or non-unit media edit is unsupported");
            return start;
        }

        void localReferences(List<Box> minf) throws IOException {
            Box dinf = one(minf, "dinf", false);
            if (dinf == null) return;
            Box dref = one(children(dinf), "dref", true);
            full(dref, 8);
            long count = u32(dref.payload + 4);
            if (count > MAX_ENTRIES) throw invalid("Too many data references");
            int at = dref.payload + 8;
            for (long i = 0; i < count; i++) {
                Box reference = box(at, dref.end);
                length(reference, 4);
                if (reference.type != tag("url ") || u32(reference.payload) != 1)
                    throw invalid("External media data is unsupported");
                at = reference.end;
            }
            if (at != dref.end) throw invalid("Invalid data reference count");
        }

        long track(Box trak, long totalSize, long seconds) throws IOException {
            List<Box> track = children(trak);
            Box mdia = one(track, "mdia", true);
            List<Box> media = children(mdia);
            Box hdlr = one(media, "hdlr", true);
            full(hdlr, 24);
            int handler = (int) u32(hdlr.payload + 8);
            if (handler != tag("vide") && handler != tag("soun")) return 0;
            long mediaStart = editStart(one(track, "edts", false));
            long target = add(mediaStart, multiply(seconds, timescale(one(media, "mdhd", true))));
            List<Box> minf = children(one(media, "minf", true));
            localReferences(minf);
            List<Box> sample = children(one(minf, "stbl", true));
            if (one(sample, "stz2", false) != null || one(sample, "saio", false) != null)
                throw invalid("Compact sizes or external auxiliary data are unsupported");
            Box stsz = one(sample, "stsz", true);
            full(stsz, 12);
            long fixedSize = u32(stsz.payload + 4), samples = u32(stsz.payload + 8);
            sampleBudget = add(sampleBudget, samples);
            if (sampleBudget > MAX_SAMPLES || samples > MAX_ENTRIES
                    || add(12, fixedSize == 0 ? multiply(samples, 4) : 0) != stsz.end - stsz.payload)
                throw invalid("Invalid sample size table or sample limit");
            Table times = table(one(sample, "stts", true), 8);
            long timestamp = 0, counted = 0, wanted = 0;
            for (int i = 0; i < times.count; i++) {
                long count = u32(times.start + i * 8), delta = u32(times.start + i * 8 + 4);
                if (count == 0 || delta == 0) throw invalid("Invalid decoding-time run");
                counted = add(counted, count);
                if (counted > samples) throw invalid("Decoding-time sample count mismatch");
                if (timestamp < target) {
                    long remaining = target - timestamp;
                    long needed = remaining / delta + (remaining % delta == 0 ? 0 : 1);
                    wanted = add(wanted, Math.min(count, needed));
                }
                timestamp = add(timestamp, multiply(count, delta));
            }
            if (counted != samples) throw invalid("Decoding-time sample count mismatch");
            if (samples != 0 && mediaStart >= timestamp) throw invalid("Media edit starts outside the track");
            Box stco = one(sample, "stco", false), co64 = one(sample, "co64", false);
            if ((stco == null) == (co64 == null)) throw invalid("Expected exactly one chunk-offset table");
            Table offsets = table(stco != null ? stco : co64, stco != null ? 4 : 8);
            Table chunks = table(one(sample, "stsc", true), 12);
            if (samples == 0) {
                if (offsets.count != 0 || chunks.count != 0) throw invalid("Chunks without samples");
                return 0;
            }
            if (chunks.count == 0 || offsets.count == 0) throw invalid("Missing sample chunks");
            long previous = 0;
            for (int i = 0; i < chunks.count; i++) {
                int at = chunks.start + i * 12;
                long first = u32(at);
                if (first <= previous || first > offsets.count || (i == 0 && first != 1)
                        || u32(at + 4) == 0 || u32(at + 8) == 0) throw invalid("Invalid sample-to-chunk run");
                previous = first;
            }
            int run = 0;
            long index = 0, result = 0;
            for (int chunk = 1; chunk <= offsets.count; chunk++) {
                if (run + 1 < chunks.count && u32(chunks.start + (run + 1) * 12) == chunk) run++;
                long perChunk = u32(chunks.start + run * 12 + 4);
                if (perChunk > samples - index) throw invalid("Chunk sample count mismatch");
                long end = stco != null ? u32(offsets.start + (chunk - 1) * 4)
                        : u64(offsets.start + (chunk - 1) * 8);
                if (end > totalSize) throw invalid("Chunk offset exceeds file length");
                for (long i = 0; i < perChunk; i++, index++) {
                    long size = fixedSize != 0 ? fixedSize : u32(stsz.payload + 12 + (int) index * 4);
                    end = add(end, size);
                    if (end > totalSize) throw invalid("Sample exceeds file length");
                    if (index < wanted && size != 0) result = Math.max(result, end);
                }
            }
            if (index != samples) throw invalid("Chunk sample count mismatch");
            return result;
        }
    }
}
