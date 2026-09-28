package com.dycomment.tv;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** ADB controls stable screenshot phases; screencap includes the real SurfaceView compositor. */
public final class FrameSwitchSelfTestActivity extends Activity {
    private static final String TAG = "Android5FrameSwitchTest";
    // Bypass prefix caching so the fixture's HTTP body gate controls the decoder exactly.
    public static final class Item { public boolean isLive = true; }
    public final List<Item> feedList = Collections.singletonList(new Item());
    public int currentIndex;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CountDownLatch blueBody = new CountDownLatch(1);
    private final CountDownLatch retireGate = new CountDownLatch(1);
    private final List<Socket> sockets = Collections.synchronizedList(new ArrayList<Socket>());
    private ServerSocket server;
    private volatile boolean running;
    private volatile boolean bodyRequested;
    private byte[] blueBytes;
    private Uri redUri, blueUri;
    private PlayerView view;
    private org.videolan.libvlc.MediaPlayer heldOld;
    private org.videolan.libvlc.MediaPlayer waitingPlayer;
    private String phase = "red";
    private boolean failed, frameReported, waitReported, finished;
    private int mutedSamples, rapidStep, outputs;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        try {
            redUri = Uri.fromFile(copyAsset("selftest-frame-red.mp4"));
            blueUri = Uri.fromFile(copyAsset("selftest-frame-blue.mp4"));
            blueBytes = readAsset("selftest-frame-blue.mp4");
            server = new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"));
            running = true;
            new Thread(this::serve, "frame-fixture-http").start();
            view = new PlayerView(this);
            setContentView(view);
            view.setOnErrorListener((p, what, extra) -> { fail("decoder error phase=" + phase); return true; });
            view.setOnInfoListener((p, what, extra) -> {
                if (what == 3 && !failed) {
                    try {
                        require(!phase.equals("blue_wait"), "blue frame arrived before body release");
                        require(nativePlayer() != null && nativePlayer().getVolume() > 0,
                                "first displayed frame must enable new audio");
                        if (heldOld != null) {
                            require(heldOld.getVolume() <= 0, "retiring audio overlaps first new frame");
                            require(mutedSamples >= 5, "waiting audio state was not sampled");
                            heldOld = null;
                            retireGate.countDown();
                            Log.i(TAG, "OLD_AND_NEW_AUDIO_HANDOFF_OK samples=" + mutedSamples);
                        }
                        frameReported = true;
                        outputs++;
                        final String readyPhase = phase;
                        // Allow the display compositor to present before ADB captures its pixels.
                        main.postDelayed(() -> {
                            if (!failed && phase.equals(readyPhase)) {
                                if (phase.equals("red")) Log.i(TAG, "FRAME_RED_READY");
                                if (phase.equals("blue")) Log.i(TAG, "FRAME_BLUE_READY");
                                if (phase.equals("rapid_final")) Log.i(TAG, "FRAME_RAPID_RED_READY");
                            }
                        }, 250);
                    } catch (Exception e) { fail(e.getMessage()); }
                }
                return false;
            });
            select(redUri);
            main.postDelayed(monitor, 40);
        } catch (Exception e) { fail("setup " + e.getClass().getSimpleName()); }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (failed || finished) return;
        try {
            String next = intent.getStringExtra("phase");
            if ("blue_wait".equals(next)) {
                require(phase.equals("red") && frameReported, "red must be displayed before blue wait");
                heldOld = nativePlayer();
                require(heldOld != null && heldOld.getVolume() > 0, "red native audio is active");
                ThreadPoolExecutor worker = (ThreadPoolExecutor) InteractionController.field(view, "retireWorker");
                worker.execute(() -> {
                    try {
                        if (!retireGate.await(18, TimeUnit.SECONDS))
                            main.post(() -> fail("retirement hold expired before audio handoff"));
                    }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                });
                phase = "blue_wait";
                select(Uri.parse("http://127.0.0.1:" + server.getLocalPort() + "/blue.mp4"));
                require(heldOld.getVolume() <= 0, "old native is muted synchronously on selection");
            } else if ("blue".equals(next)) {
                require(phase.equals("blue_wait") && waitReported, "blue wait must be verified first");
                phase = "blue";
                blueBody.countDown();
            } else if ("rapid".equals(next)) {
                require(phase.equals("blue") && frameReported, "blue must be displayed before rapid switch");
                phase = "rapid";
                main.post(rapid);
            } else if ("done".equals(next)) {
                require(phase.equals("rapid_final") && frameReported && rapidStep == 10,
                        "rapid switch must finish on red");
                require(outputs >= 3 && mutedSamples >= 5, "all displayed and waiting phases observed");
                finished = true;
                Log.i(TAG, "PASS API21_REAL_SURFACE_FRAME_SWITCH_AUDIO_HANDOFF");
            } else throw new IllegalStateException("unknown fixture phase");
        } catch (Exception e) { fail(e.getMessage()); }
    }

    private void select(Uri source) throws Exception {
        org.videolan.libvlc.MediaPlayer previous = nativePlayer();
        frameReported = false;
        view.setVideoURI(source);
        // release() is posted to the main loop, so the old reference is valid in this stack.
        if (previous != null) require(previous.getVolume() <= 0, "selection left retiring audio audible");
    }

    private final Runnable rapid = new Runnable() {
        @Override public void run() {
            if (failed || finished) return;
            try {
                if (rapidStep == 10) {
                    phase = "rapid_final";
                    select(redUri);
                    return;
                }
                select((rapidStep++ % 2) == 0 ? redUri : blueUri);
                main.postDelayed(this, 120);
            } catch (Exception e) { fail(e.getMessage()); }
        }
    };

    private final Runnable monitor = new Runnable() {
        @Override public void run() {
            if (failed || finished) return;
            try {
                if (heldOld != null) require(heldOld.getVolume() <= 0, "old native became audible while waiting");
                org.videolan.libvlc.MediaPlayer active = nativePlayer();
                if (!frameReported && active != null) {
                    require(active.getVolume() <= 0, "new native audible before displayed frame callback");
                    if (phase.equals("blue_wait") && bodyRequested) {
                        if (waitingPlayer == null) {
                            waitingPlayer = active;
                            // A retirement completion wakes this same method while a new
                            // native instance can still be preparing a slow response.
                            java.lang.reflect.Method wake = PlayerView.class.getDeclaredMethod("queueOpen");
                            wake.setAccessible(true);
                            wake.invoke(view);
                        }
                        require(active == waitingPlayer, "retirement wakeup replaced the preparing native player");
                        mutedSamples++;
                        if (mutedSamples >= 5 && !waitReported) {
                            waitReported = true;
                            Log.i(TAG, "FRAME_BLUE_WAIT_MUTED");
                        }
                    }
                }
                main.postDelayed(this, 40);
            } catch (Exception e) { fail(e.getMessage()); }
        }
    };

    private org.videolan.libvlc.MediaPlayer nativePlayer() throws Exception {
        return view == null ? null : (org.videolan.libvlc.MediaPlayer) InteractionController.field(view, "player");
    }

    private File copyAsset(String name) throws IOException {
        File file = new File(getCacheDir(), name);
        try (FileOutputStream out = new FileOutputStream(file)) { out.write(readAsset(name)); }
        return file;
    }

    private byte[] readAsset(String name) throws IOException {
        try (InputStream in = getAssets().open(name); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            return out.toByteArray();
        }
    }

    private void serve() {
        while (running) {
            try {
                Socket socket = server.accept();
                sockets.add(socket);
                new Thread(() -> respond(socket), "frame-fixture-body").start();
            } catch (IOException e) { if (running) main.post(() -> fail("fixture accept")); }
        }
    }

    private void respond(Socket socket) {
        try (Socket client = socket) {
            client.setSoTimeout(15000);
            BufferedReader input = new BufferedReader(new InputStreamReader(client.getInputStream(), "US-ASCII"));
            String request = input.readLine();
            if (request == null) return;
            int start = 0, end = blueBytes.length - 1;
            boolean range = false;
            String line;
            while ((line = input.readLine()) != null && !line.isEmpty()) {
                if (line.toLowerCase(Locale.US).startsWith("range: bytes=")) {
                    String[] bounds = line.substring(line.indexOf('=') + 1).split("-", -1);
                    start = Integer.parseInt(bounds[0]);
                    if (bounds.length > 1 && !bounds[1].isEmpty()) end = Math.min(end, Integer.parseInt(bounds[1]));
                    range = true;
                }
            }
            require(start >= 0 && start <= end, "invalid fixture range");
            OutputStream out = client.getOutputStream();
            String headers = "HTTP/1.1 " + (range ? "206 Partial Content" : "200 OK")
                    + "\r\nContent-Type: video/mp4\r\nAccept-Ranges: bytes\r\nContent-Length: " + (end - start + 1)
                    + (range ? "\r\nContent-Range: bytes " + start + "-" + end + "/" + blueBytes.length : "")
                    + "\r\nConnection: close\r\n\r\n";
            out.write(headers.getBytes("US-ASCII"));
            out.flush();
            if (!request.startsWith("HEAD ")) {
                bodyRequested = true;
                if (!blueBody.await(18, TimeUnit.SECONDS)) throw new IOException("body release timeout");
                out.write(blueBytes, start, end - start + 1);
                out.flush();
            }
        } catch (Exception e) {
            if (running && blueBody.getCount() != 0) main.post(() -> fail("fixture body gate " + e.getClass().getSimpleName()));
        } finally { sockets.remove(socket); }
    }

    private static void require(boolean ok, String reason) {
        if (!ok) throw new IllegalStateException(reason);
    }

    private void fail(String reason) {
        if (failed) return;
        failed = true;
        Log.e(TAG, "FAIL " + reason);
        retireGate.countDown();
        blueBody.countDown();
    }

    @Override protected void onDestroy() {
        running = false;
        finished = true;
        main.removeCallbacksAndMessages(null);
        retireGate.countDown();
        blueBody.countDown();
        try { if (server != null) server.close(); } catch (IOException ignored) { }
        synchronized (sockets) {
            for (Socket socket : sockets) try { socket.close(); } catch (IOException ignored) { }
        }
        if (view != null) view.stopPlayback();
        super.onDestroy();
    }
}
