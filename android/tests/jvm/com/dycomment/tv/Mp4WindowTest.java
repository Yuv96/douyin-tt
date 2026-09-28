package com.dycomment.tv;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;

/** Pure JVM fixtures; compile and execute only through GitHub Actions. */
public final class Mp4WindowTest {
    interface Checked { void run() throws Exception; }

    static void equal(long expected, long actual, String reason) {
        if (actual != expected) throw new AssertionError(reason + ": expected " + expected + ", got " + actual);
    }

    static void fails(Checked operation, String reason) throws Exception {
        try { operation.run(); }
        catch (IOException expected) { return; }
        throw new AssertionError("Must reject " + reason);
    }

    static byte[] join(byte[]... parts) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (byte[] part : parts) output.write(part);
        return output.toByteArray();
    }

    static byte[] words(long... values) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        DataOutputStream data = new DataOutputStream(output);
        for (long value : values) data.writeInt((int) value);
        return output.toByteArray();
    }

    static byte[] longs(long... values) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        DataOutputStream data = new DataOutputStream(output);
        for (long value : values) data.writeLong(value);
        return output.toByteArray();
    }

    static byte[] box(String type, byte[]... parts) throws IOException {
        byte[] payload = join(parts);
        return join(words(8 + payload.length), type.getBytes("US-ASCII"), payload);
    }

    static byte[] full(String type, byte[]... parts) throws IOException {
        return box(type, words(0), join(parts));
    }

    static byte[] large(String type, byte[] payload) throws IOException {
        return join(words(1), type.getBytes("US-ASCII"), longs(16 + payload.length), payload);
    }

    static byte[] times(long... runs) throws IOException {
        return full("stts", words(runs.length / 2), words(runs));
    }

    static byte[] chunks(long... runs) throws IOException {
        return full("stsc", words(runs.length / 3), words(runs));
    }

    static byte[] sizes(long... samples) throws IOException {
        return full("stsz", words(0, samples.length), words(samples));
    }

    static byte[] fixed(long size, long count) throws IOException {
        return full("stsz", words(size, count));
    }

    static byte[] offsets(boolean wide, long... values) throws IOException {
        return full(wide ? "co64" : "stco", words(values.length), wide ? longs(values) : words(values));
    }

    static byte[] edit(boolean wide, long start, long rate) throws IOException {
        return box("edts", box("elst", words(wide ? 0x01000000 : 0, 1),
                wide ? longs(100000, start) : words(100000, start), words(rate)));
    }

    static byte[] track(String kind, long scale, boolean version1, byte[] edit, byte[]... tables) throws IOException {
        byte[] mdhd = box("mdhd", version1
                ? join(words(0x01000000), longs(0, 0), words(scale), longs(0), words(0))
                : words(0, 0, 0, scale, 0, 0));
        byte[] handler = full("hdlr", words(0), kind.getBytes("US-ASCII"), words(0, 0, 0));
        byte[] local = box("dinf", full("dref", words(1), box("url ", words(1))));
        return box("trak", edit, box("mdia", mdhd, handler, box("minf", local, box("stbl", tables))));
    }

    static byte[] track(byte[]... tables) throws IOException {
        return track("vide", 1000, false, new byte[0], tables);
    }

    static byte[] simple() throws IOException {
        return track(times(15, 1000), chunks(1, 5, 1), fixed(100, 15), offsets(false, 1000, 4000, 7000));
    }

    static void windows() throws Exception {
        long[] videoSizes = new long[15];
        for (int i = 0; i < videoSizes.length; i++) videoSizes[i] = 100 + i;
        byte[] video = track(times(15, 1000), chunks(1, 5, 1), sizes(videoSizes), offsets(false, 1000, 4000, 7000));
        byte[] audio = track("soun", 48000, true, new byte[0], times(24, 24000), chunks(1, 4, 1),
                fixed(20, 24), offsets(false, 1600, 2700, 4700, 5700, 6500, 8000));
        equal(4535, Mp4Window.prefixEnd(box("moov", video), 10000, 10), "variable sizes use actual first ten samples");
        equal(6580, Mp4Window.prefixEnd(box("moov", video, audio), 10000, 10), "audio and video use their own timescales");
        equal(4500, Mp4Window.prefixEnd(box("moov", simple()), 10000, 10), "boundary excludes a sample starting at ten seconds");
        equal(7500, Mp4Window.prefixEnd(box("moov", simple()), 10000, 30), "short track includes all samples");
        byte[] vfr = track(times(3, 2000, 4, 1500), chunks(1, 2, 1, 3, 3, 1),
                sizes(10, 20, 30, 40, 50, 60, 70), offsets(false, 1000, 2000, 3000));
        equal(3110, Mp4Window.prefixEnd(box("moov", vfr), 10000, 10), "VFR horizon can end part-way through final chunk");
        equal(2030, Mp4Window.prefixEnd(box("moov", vfr), 10000, 5), "include sample spanning requested boundary");
        byte[] wide = track("vide", 1000, true, new byte[0], times(2, 1000), chunks(1, 2, 1),
                fixed(50, 2), offsets(true, 0x100000000L + 100));
        equal(0x100000000L + 150, Mp4Window.prefixEnd(box("moov", wide), 0x100001000L, 1), "co64 offsets stay 64-bit");
        equal(4500, Mp4Window.prefixEnd(large("moov", simple()), 10000, 10), "64-bit box header");
        byte[] nonmedia = track("meta", 1000, false, new byte[0]);
        equal(4500, Mp4Window.prefixEnd(box("moov", nonmedia, simple()), 10000, 10), "non-media track contributes no sample window");
        for (boolean v1 : new boolean[] {false, true}) {
            byte[] edited = track("vide", 1000, v1, edit(v1, 1024, 65536), times(15, 1000),
                    chunks(1, 5, 1), fixed(100, 15), offsets(false, 1000, 4000, 7000));
            equal(7200, Mp4Window.prefixEnd(box("moov", edited), 10000, 10), "single media edit retains decode preroll");
        }
    }

    static void rejection() throws Exception {
        byte[] good = box("moov", simple());
        fails(() -> Mp4Window.prefixEnd(Arrays.copyOf(good, good.length - 1), 10000, 10), "truncated moov");
        fails(() -> Mp4Window.prefixEnd(join(good, words(0)), 10000, 10), "bytes after moov");
        fails(() -> Mp4Window.prefixEnd(box("free", simple()), 10000, 10), "wrong root box");
        fails(() -> Mp4Window.prefixEnd(good, good.length - 1, 10), "metadata larger than file");
        fails(() -> Mp4Window.prefixEnd(good, 10000, 0), "nonpositive horizon");
        fails(() -> Mp4Window.prefixEnd(good, 10000, Long.MAX_VALUE), "time conversion overflow");
        fails(() -> Mp4Window.prefixEnd(box("moov", box("mvex"), simple()), 10000, 10), "fragmented media");
        fails(() -> Mp4Window.prefixEnd(box("moov", track(times(15, 1000), chunks(1, 5, 1),
                fixed(100, 15), offsets(false, 1000, 4000, 9900))), 10000, 1), "out-of-range samples even beyond horizon");
        fails(() -> Mp4Window.prefixEnd(box("moov", track(times(2, 1000), chunks(1, 2, 1),
                fixed(10, 2), offsets(true, Long.MAX_VALUE - 5))), Long.MAX_VALUE, 1), "sample-offset arithmetic overflow");
        fails(() -> Mp4Window.prefixEnd(box("moov", track(times(2, 1000), chunks(1, 2, 1),
                fixed(10, 2), offsets(true, Long.MIN_VALUE))), Long.MAX_VALUE, 1), "unsigned co64 above signed range");
        fails(() -> Mp4Window.prefixEnd(box("moov", track(times(14, 1000), chunks(1, 5, 1),
                fixed(100, 15), offsets(false, 1000, 4000, 7000))), 10000, 10), "stts sample mismatch");
        fails(() -> Mp4Window.prefixEnd(box("moov", track(times(15, 1000), chunks(1, 4, 1),
                fixed(100, 15), offsets(false, 1000, 4000, 7000))), 10000, 10), "stsc sample mismatch");
        fails(() -> Mp4Window.prefixEnd(box("moov", track(times(15, 1000), chunks(2, 5, 1),
                fixed(100, 15), offsets(false, 1000, 4000, 7000))), 10000, 10), "chunk runs must start at one");
        fails(() -> Mp4Window.prefixEnd(box("moov", track(times(15, 1000), chunks(1, 5, 1, 1, 5, 1),
                fixed(100, 15), offsets(false, 1000, 4000, 7000))), 10000, 10), "unordered chunk runs");
        fails(() -> Mp4Window.prefixEnd(box("moov", track(times(15, 0), chunks(1, 5, 1),
                fixed(100, 15), offsets(false, 1000, 4000, 7000))), 10000, 10), "zero sample delta");
        fails(() -> Mp4Window.prefixEnd(box("moov", track("soun", 0, false, new byte[0],
                times(1, 1000), chunks(1, 1, 1), fixed(1, 1), offsets(false, 1000))), 10000, 10), "zero timescale");
        fails(() -> Mp4Window.prefixEnd(box("moov", track(times(1, 1000), chunks(1, 1, 1),
                fixed(1, 1), offsets(false, 1000), offsets(true, 1000))), 10000, 10), "ambiguous chunk offsets");
        fails(() -> Mp4Window.prefixEnd(box("moov", track(times(1, 1000), chunks(1, 1, 1),
                fixed(1, 1), offsets(false, 1000), fixed(1, 1))), 10000, 10), "duplicate required tables");
        fails(() -> Mp4Window.prefixEnd(box("moov", track(times(1, 1000), chunks(1, 1, 1),
                full("stsz", words(0, 1)), offsets(false, 1000))), 10000, 10), "truncated variable size table");
        fails(() -> Mp4Window.prefixEnd(box("moov", track(times(1000001, 1), chunks(1, 1000001, 1),
                fixed(1, 1000001), offsets(false, 1000))), 2000000, 1), "bounded sample count");
        fails(() -> Mp4Window.prefixEnd(box("moov", track("vide", 1000, false, edit(false, -1, 65536),
                times(1, 1000), chunks(1, 1, 1), fixed(1, 1), offsets(false, 1000))), 10000, 10), "empty edit");
        fails(() -> Mp4Window.prefixEnd(box("moov", track("vide", 1000, false, edit(false, 0, 131072),
                times(1, 1000), chunks(1, 1, 1), fixed(1, 1), offsets(false, 1000))), 10000, 10), "non-unit edit rate");
        fails(() -> Mp4Window.prefixEnd(box("moov", track("vide", 1000, true, edit(true, Long.MAX_VALUE - 500, 65536),
                times(1, 1000), chunks(1, 1, 1), fixed(1, 1), offsets(false, 1000))), 10000, 1), "edit horizon arithmetic overflow");
        fails(() -> Mp4Window.prefixEnd(box("moov", track("vide", 1000, false, edit(false, 1000, 65536),
                times(1, 1000), chunks(1, 1, 1), fixed(1, 1), offsets(false, 1000))), 10000, 1), "edit beyond media duration");
        fails(() -> Mp4Window.prefixEnd(box("moov", track("vide", 1000, false,
                box("edts", full("elst", words(2, 1000, 0, 65536, 1000, 0, 65536))),
                times(1, 1000), chunks(1, 1, 1), fixed(1, 1), offsets(false, 1000))), 10000, 10), "multiple edit segments");
        fails(() -> Mp4Window.prefixEnd(box("moov", track(times(1, 1000), chunks(1, 1, 1),
                fixed(1, 1), offsets(false, 1000), box("saio"))), 10000, 10), "external auxiliary offsets");
        fails(() -> Mp4Window.prefixEnd(box("moov", join(words(0), "free".getBytes("US-ASCII"))),
                10000, 10), "open-ended nested box");
    }

    public static void main(String[] args) throws Exception {
        windows();
        rejection();
        System.out.println("PASS MP4_WINDOW_VARIABLE_TIMING_AUDIO_VIDEO_CO64_EDITS_BOUNDS");
    }
}
