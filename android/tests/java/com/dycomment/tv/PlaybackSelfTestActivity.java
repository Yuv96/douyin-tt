package com.dycomment.tv;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Deterministic API-21 playback/cache check, with generated (non-network) fixtures. */
public final class PlaybackSelfTestActivity extends Activity {
    public static final class Item {
        public String videoUrl, awemeId = "fixture";
        public int videoWidth = 1280, videoHeight = 720;
        public boolean isLive;
        public List<String> imageUrls = new ArrayList<String>();

        Item(String url) {
            videoUrl = url;
        }
    }

    public List<Item> feedList = new ArrayList<Item>();
    public int currentIndex;
    public boolean filterVertical, cameFromProfile;
    private final Handler handler = new Handler();
    private final AtomicInteger secondRequests = new AtomicInteger();
    private PlayerView view;
    private ServerSocket server;
    private volatile boolean running;
    private int outputs, stage, ticks, requestsAtSwitch;
    private int lastPosition;

    protected void onCreate(Bundle b) {
        super.onCreate(b);
        try {
            File cache = new File(getCacheDir(), "next-video-v1");
            File[] files = cache.listFiles();
            if (files != null) for (File f : files) f.delete();
            server = new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"));
            running = true;
            new Thread(() -> serve(), "fixture-http").start();
            String root = "http://127.0.0.1:" + server.getLocalPort();
            feedList.add(new Item(root + "/one.mp4"));
            feedList.add(new Item(root + "/two.mp4"));
            view = new PlayerView(this);
            setContentView(view);
            view.setOnInfoListener(
                    (p, what, extra) -> {
                        if (what == 3) outputs++;
                        return false;
                    });
            view.setOnErrorListener(
                    (p, w, e) -> {
                        fail("player error");
                        return true;
                    });
            view.setVideoURI(Uri.parse(feedList.get(0).videoUrl));
            handler.postDelayed(check, 500);
        } catch (Exception e) {
            fail(e.getClass().getSimpleName());
        }
    }

    private final Runnable check =
            new Runnable() {
                public void run() {
                    if (stage < 0) return;
                    if (++ticks > 110) {
                        fail("timeout stage=" + stage + " outputs=" + outputs);
                        return;
                    }
                    int position = view.getCurrentPosition();
                    if (stage == 0 && outputs > 0 && position > 2500) {
                        File[] f =
                                new File(getCacheDir(), "next-video-v1")
                                        .listFiles((d, n) -> n.endsWith(".mp4"));
                        if (f != null && f.length > 0) {
                            Log.i("Android5SelfTest", "FIRST_VIDEO_OK position=" + position);
                            requestsAtSwitch = secondRequests.get();
                            if (requestsAtSwitch < 1) {
                                fail("next video was not prefetched");
                                return;
                            }
                            currentIndex = 1;
                            outputs = 0;
                            stage = 1;
                            view.setVideoURI(Uri.parse(feedList.get(1).videoUrl));
                        }
                    } else if (stage == 1 && outputs > 0 && position > 1800) {
                        if (secondRequests.get() != requestsAtSwitch) {
                            fail("cache miss: network requested again");
                            return;
                        }
                        Log.i(
                                "Android5SelfTest",
                                "SECOND_VIDEO_OFFLINE_OK position="
                                        + position
                                        + " requests="
                                        + secondRequests.get());
                        view.pause();
                        lastPosition = position;
                        stage = 2;
                    } else if (stage == 2) {
                        if (Math.abs(position - lastPosition) > 600) {
                            fail("pause did not hold");
                            return;
                        }
                        view.seekTo(500);
                        view.setPlaybackSpeed(1.25f);
                        view.start();
                        stage = 3;
                    } else if (stage == 3 && position < 1600) {
                        // A seek is asynchronous. Observe the new position before checking progress
                        // again.
                        stage = 4;
                    } else if (stage == 4 && position > 2200 && view.isPlaying()) {
                        try {
                            File portrait = new File(getCacheDir(), "test-portrait.mp4");
                            try (InputStream in = getAssets().open("selftest-portrait.mp4");
                                    OutputStream out = new FileOutputStream(portrait)) {
                                byte[] b = new byte[8192];
                                int n;
                                while ((n = in.read(b)) != -1) out.write(b, 0, n);
                            }
                            outputs = 0;
                            stage = 5;
                            view.setVideoURI(Uri.fromFile(portrait));
                        } catch (Exception e) {
                            fail("portrait fixture");
                            return;
                        }
                    } else if (stage == 5 && outputs > 0 && position > 1200) {
                        android.view.View surface = view.getChildAt(0);
                        if (Math.abs(surface.getLeft() + surface.getRight() - view.getWidth()) > 2
                                || Math.abs(
                                                surface.getTop()
                                                        + surface.getBottom()
                                                        - view.getHeight())
                                        > 2) {
                            fail("portrait not centered");
                            return;
                        }
                        float aspect = (float) surface.getWidth() / surface.getHeight();
                        try {
                            org.videolan.libvlc.MediaPlayer nativePlayer =
                                    (org.videolan.libvlc.MediaPlayer)
                                            InteractionController.field(view, "player");
                            org.videolan.libvlc.interfaces.IMedia.VideoTrack track =
                                    nativePlayer.getCurrentVideoTrack();
                            if (track == null || track.width != 360 || track.height != 640) {
                                fail("portrait track dimensions");
                                return;
                            }
                            boolean nativeLayout =
                                    (Integer) InteractionController.field(view, "videoWidth") == 0;
                            if (nativeLayout) {
                                if (surface.getWidth() != view.getWidth()
                                        || surface.getHeight() != view.getHeight()
                                        || nativePlayer.getScale() != 0) {
                                    fail("OpenGL must fit inside full centered surface");
                                    return;
                                }
                            } else if (Math.abs(aspect - 360f / 640f) > 0.025f) {
                                fail("portrait aspect " + aspect);
                                return;
                            }
                            Log.i(
                                    "Android5SelfTest",
                                    "PORTRAIT_LAYOUT native="
                                            + nativeLayout
                                            + " track="
                                            + track.width
                                            + "x"
                                            + track.height);
                        } catch (Exception e) {
                            fail("portrait layout inspection");
                            return;
                        }
                        Log.i(
                                "Android5SelfTest",
                                "PASS API21_H264_HIGH_BASELINE_VIDEO_OUTPUT_CACHE_PAUSE_SEEK_RATE_PORTRAIT_CENTER");
                        stage = 6;
                        return;
                    }
                    handler.postDelayed(this, 500);
                }
            };

    private void fail(String message) {
        stage = -1;
        Log.e("Android5SelfTest", "FAIL " + message);
    }

    private void serve() {
        while (running) {
            try {
                final Socket socket = server.accept();
                new Thread(() -> handle(socket), "fixture-request").start();
            } catch (IOException e) {
                return;
            }
        }
    }

    private void handle(Socket socket) {
        try (Socket s = socket) {
            BufferedReader reader =
                    new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"));
            String line = reader.readLine();
            if (line == null) return;
            String path = line.split(" ")[1];
            boolean second = path.equals("/two.mp4");
            if (second) secondRequests.incrementAndGet();
            String range = null;
            while ((line = reader.readLine()) != null && !line.isEmpty())
                if (line.toLowerCase(Locale.US).startsWith("range:"))
                    range = line.substring(6).trim();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream in =
                    getAssets().open(second ? "selftest-baseline.mp4" : "selftest-high.mp4")) {
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) > 0) bytes.write(buffer, 0, n);
            }
            byte[] body = bytes.toByteArray();
            int start = 0, end = body.length - 1;
            if (range != null && range.startsWith("bytes=")) {
                String[] parts = range.substring(6).split("-", -1);
                if (!parts[0].isEmpty()) start = Integer.parseInt(parts[0]);
                if (parts.length > 1 && !parts[1].isEmpty())
                    end = Math.min(end, Integer.parseInt(parts[1]));
            }
            OutputStream out = s.getOutputStream();
            String header =
                    "HTTP/1.1 "
                            + (range == null ? "200 OK" : "206 Partial Content")
                            + "\r\n"
                            + "Content-Type: video/mp4\r\n"
                            + "Accept-Ranges: bytes\r\n"
                            + "Content-Length: "
                            + (end - start + 1)
                            + "\r\n";
            if (range != null)
                header += "Content-Range: bytes " + start + "-" + end + "/" + body.length + "\r\n";
            out.write((header + "Connection: close\r\n\r\n").getBytes("UTF-8"));
            out.write(body, start, end - start + 1);
            out.flush();
        } catch (Exception ignored) {
        }
    }

    protected void onDestroy() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        try {
            if (server != null) server.close();
        } catch (Exception ignored) {
        }
        if (view != null) view.stopPlayback();
        super.onDestroy();
    }
}
