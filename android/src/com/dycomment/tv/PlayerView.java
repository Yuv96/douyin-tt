package com.dycomment.tv;

import android.content.Context;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Gravity;
import android.view.SurfaceView;
import android.widget.FrameLayout;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.interfaces.IVLCVout;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;

/** API-21 player. Original callback descriptors are retained for the Lite UI. */
public class PlayerView extends FrameLayout implements IVLCVout.OnNewVideoLayoutListener {
    public static final int STATE_IDLE = 0,
            STATE_PREPARING = 1,
            STATE_PREPARED = 2,
            STATE_PLAYING = 3,
            STATE_PAUSED = 4,
            STATE_COMPLETED = 5,
            STATE_ERROR = 6;

    public interface OnPreparedListener {
        void onPrepared(MediaPlayer p);
    }

    public interface OnCompletionListener {
        void onCompletion(MediaPlayer p);
    }

    public interface OnErrorListener {
        boolean onError(MediaPlayer p, int what, int extra);
    }

    public interface OnInfoListener {
        boolean onInfo(MediaPlayer p, int what, int extra);
    }

    public interface OnBufferingListener {
        void onBuffering(int percent);
    }

    private static LibVLC engine;
    private final SurfaceView surface;
    private final NextVideoCache cache;
    private org.videolan.libvlc.MediaPlayer player;
    private Uri pending;
    private Map<String, String> pendingHeaders;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean retiring, detached, videoOutput;
    private long stalledAt;
    private final Runnable openLatest = () -> openPending();
    private final Runnable watchdog =
            new Runnable() {
                public void run() {
                    if (detached || pending == null || state == STATE_ERROR || state == STATE_IDLE)
                        return;
                    long now = SystemClock.elapsedRealtime();
                    if (wantPlay
                            && ((!videoOutput && now - openedAt > 20000)
                                    || (stalledAt > 0 && now - stalledAt > 20000))) {
                        failPlayback("LOAD_TIMEOUT");
                        return;
                    }
                    main.postDelayed(this, 500);
                }
            };
    private int state, buffer, generation;
    private boolean wantPlay, prepared, completed;
    private OnPreparedListener onPrepared;
    private OnCompletionListener onCompletion;
    private OnErrorListener onError;
    private OnInfoListener onInfo;
    private OnBufferingListener onBuffering;
    private long openedAt;
    private long surfaceResumePosition = -1;
    private int videoWidth, videoHeight, codedWidth, codedHeight, sarNum = 1, sarDen = 1;

    public PlayerView(Context c) {
        this(c, null);
    }

    public PlayerView(Context c, AttributeSet a) {
        this(c, a, 0);
    }

    public PlayerView(Context c, AttributeSet a, int style) {
        super(c, a, style);
        setBackgroundColor(0xff000000);
        setFocusable(true);
        setFocusableInTouchMode(true);
        surface = new SurfaceView(c);
        addView(surface, new LayoutParams(-1, -1, Gravity.CENTER));
        cache = new NextVideoCache(c);
    }

    private synchronized LibVLC engine() {
        if (engine == null) {
            engine =
                    new LibVLC(
                            getContext().getApplicationContext(),
                            new ArrayList<String>(
                                    Arrays.asList(
                                            "--avcodec-hw=none",
                                            "--network-caching=1000",
                                            "--file-caching=200",
                                            "--no-video-title-show",
                                            "--no-snapshot-preview")));
            engine.setUserAgent("douyin-tt 0.1.5", NextVideoCache.USER_AGENT);
        }
        return engine;
    }

    public void setVideoURI(Uri uri) {
        setVideoURI(uri, null);
    }

    public void setVideoURI(Uri uri, Map<String, String> headers) {
        releasePlayer();
        pending = uri;
        pendingHeaders = headers;
        prepared = false;
        completed = false;
        wantPlay = true;
        buffer = 0;
        videoOutput = false;
        stalledAt = 0;
        surfaceResumePosition = -1;
        videoWidth = videoHeight = codedWidth = codedHeight = 0;
        sarNum = sarDen = 1;
        surface.setLayoutParams(new LayoutParams(-1, -1, Gravity.CENTER));
        state = STATE_PREPARING;
        openedAt = SystemClock.elapsedRealtime();
        cache.onSelection();
        PlaybackCoordinator.loading(getContext());
        main.removeCallbacks(watchdog);
        main.postDelayed(watchdog, 500);
        queueOpen();
    }

    private void queueOpen() {
        main.removeCallbacks(openLatest);
        if (!retiring && !detached && pending != null && state == STATE_PREPARING)
            main.postDelayed(
                    openLatest, 100); // Collapse rapid remote repeats to the latest selection.
    }

    private void openPending() {
        if (retiring || detached || pending == null || state != STATE_PREPARING) return;
        final Uri uri = pending;
        final Map<String, String> headers = pendingHeaders;
        final int token = generation;
        try {
            String original = NextVideoCache.originalUrl(uri.toString());
            Uri source = Uri.parse(cache.playbackUrl(getContext(), original));
            player = new org.videolan.libvlc.MediaPlayer(engine());
            attachVideo();
            player.setEventListener(
                    event -> {
                        if (token == generation) handleEvent(event);
                    });
            Media media = new Media(engine(), source);
            media.setHWDecoderEnabled(false, false);
            media.addOption(":avcodec-hw=none");
            String ref = headers == null ? "https://www.douyin.com/" : headers.get("Referer");
            if (ref != null)
                media.addOption(":http-referrer=" + ref.replace("\r", "").replace("\n", ""));
            player.setMedia(media);
            media.release();
            setKeepScreenOn(true);
            player.play();
            Log.i("Android5Player", "OPEN decoder=software");
        } catch (Exception | LinkageError e) {
            Log.e("Android5Player", "OPEN_FAILED " + e.getClass().getSimpleName());
            failPlayback("OPEN_FAILED");
        }
    }

    private void handleEvent(org.videolan.libvlc.MediaPlayer.Event e) {
        switch (e.type) {
            case org.videolan.libvlc.MediaPlayer.Event.Playing:
                state = STATE_PLAYING;
                if (!prepared) {
                    prepared = true;
                    setPlaybackSpeed(
                            getContext()
                                    .getSharedPreferences("dy_config", 0)
                                    .getFloat("playback_speed", 1f));
                    if (onPrepared != null) onPrepared.onPrepared(null);
                    Log.i(
                            "Android5Player",
                            "PLAYING startup_ms="
                                    + (android.os.SystemClock.elapsedRealtime() - openedAt));
                }
                if (!wantPlay) pause();
                else if (videoOutput && buffer >= 100) cache.scheduleNext(getContext());
                break;
            case org.videolan.libvlc.MediaPlayer.Event.Vout:
                if (e.getVoutCount() > 0) {
                    if (surfaceResumePosition >= 0 && player != null) {
                        long position = surfaceResumePosition;
                        surfaceResumePosition = -1;
                        // Surface destruction disables the video track. On re-enable,
                        // flush orphaned P frames by decoding from a keyframe to this time.
                        player.setTime(position, false);
                        Log.i("Android5Player", "RESTORE_POSITION ms=" + position);
                    }
                    fitVideo();
                    videoOutput = true;
                    stalledAt = 0;
                    PlaybackCoordinator.ready(getContext());
                    if (wantPlay && buffer >= 100) cache.scheduleNext(getContext());
                    Log.i("Android5Player", "VIDEO_OUTPUT count=" + e.getVoutCount());
                    if (onInfo != null) onInfo.onInfo(null, 3, 0);
                }
                break;
            case org.videolan.libvlc.MediaPlayer.Event.TimeChanged:
                if (wantPlay) cache.position(e.getTimeChanged());
                break;
            case org.videolan.libvlc.MediaPlayer.Event.Buffering:
                buffer = Math.round(e.getBuffering());
                if (onBuffering != null) onBuffering.onBuffering(buffer);
                if (onInfo != null) onInfo.onInfo(null, buffer < 100 ? 701 : 702, 0);
                if (buffer < 100) {
                    if (stalledAt == 0) stalledAt = SystemClock.elapsedRealtime();
                    cache.suspend();
                } else {
                    stalledAt = 0;
                    if (videoOutput && wantPlay) cache.scheduleNext(getContext());
                }
                break;
            case org.videolan.libvlc.MediaPlayer.Event.EndReached:
                if (!completed) {
                    completed = true;
                    state = STATE_COMPLETED;
                    setKeepScreenOn(false);
                    main.removeCallbacks(watchdog);
                    cache.suspend();
                    if (onCompletion != null) onCompletion.onCompletion(null);
                }
                break;
            case org.videolan.libvlc.MediaPlayer.Event.EncounteredError:
                failPlayback("DECODE_OR_NETWORK_ERROR");
                break;
        }
    }

    private void failPlayback(String reason) {
        releasePlayer();
        state = STATE_ERROR;
        wantPlay = false;
        cache.suspend();
        main.removeCallbacks(watchdog);
        Log.e("Android5Player", reason);
        if (onError != null) onError.onError(null, 1, -1);
    }

    public void setPlaybackSpeed(float rate) {
        if (player != null) player.setRate(Math.max(0.25f, Math.min(3f, rate)));
    }

    public void start() {
        wantPlay = true;
        if (player == null) {
            if (state == STATE_PREPARING) queueOpen();
            else if (pending != null) setVideoURI(pending, pendingHeaders);
            return;
        }
        if (state == STATE_COMPLETED) {
            setVideoURI(pending);
            return;
        }
        attachVideo();
        if (state == STATE_PAUSED || state == STATE_PREPARED) {
            player.play();
            state = STATE_PLAYING;
            setKeepScreenOn(true);
        }
    }

    /**
     * AWindow detaches itself when an Activity's SurfaceView is destroyed. Rebind on return,
     * including when Android has not created the new surface yet.
     */
    private void attachVideo() {
        if (player == null || detached) return;
        IVLCVout vout = player.getVLCVout();
        if (!vout.areViewsAttached()) {
            final int token = generation;
            if (prepared && player.isSeekable()) {
                surfaceResumePosition = Math.max(0, player.getTime());
                cache.position(surfaceResumePosition);
            }
            videoOutput = false;
            stalledAt = 0;
            openedAt = SystemClock.elapsedRealtime();
            player.setAspectRatio(null);
            player.setScale(0);
            vout.setVideoView(surface);
            vout.attachViews(
                    (v, w, h, vw, vh, sn, sd) -> {
                        if (token == generation && player != null)
                            onNewVideoLayout(v, w, h, vw, vh, sn, sd);
                    });
            main.removeCallbacks(watchdog);
            main.postDelayed(watchdog, 500);
            Log.i("Android5Player", "SURFACE_ATTACHED");
        }
        if (getWidth() > 0 && getHeight() > 0) vout.setWindowSize(getWidth(), getHeight());
        fitVideo();
    }

    public void pause() {
        wantPlay = false;
        if (player != null && prepared) {
            player.pause();
            state = STATE_PAUSED;
        }
        setKeepScreenOn(false);
        cache.suspend();
    }

    public void stopPlayback() {
        pending = null;
        pendingHeaders = null;
        wantPlay = false;
        main.removeCallbacks(watchdog);
        releasePlayer();
        state = STATE_IDLE;
        cache.onSelection();
    }

    public void resumeIfNeeded() {
        start();
    }

    public void seekTo(int ms) {
        if (player != null && prepared) {
            cache.seek();
            player.setTime(Math.max(0, ms));
        }
    }

    public int getCurrentPosition() {
        return player == null ? 0 : (int) Math.max(0, player.getTime());
    }

    public int getDuration() {
        return player == null ? 0 : (int) Math.max(0, player.getLength());
    }

    public boolean isPlaying() {
        return player != null && player.isPlaying();
    }

    /** Playback intent remains true while preparation or buffering temporarily has no output. */
    public boolean wantsPlayback() {
        return wantPlay;
    }

    public int getBufferPercent() {
        return buffer;
    }

    public int getPlayState() {
        return state;
    }

    public void resetCompletion() {
        completed = false;
    }

    public void setOnPreparedListener(OnPreparedListener l) {
        onPrepared = l;
    }

    public void setOnCompletionListener(OnCompletionListener l) {
        onCompletion = l;
    }

    public void setOnErrorListener(OnErrorListener l) {
        onError = l;
    }

    public void setOnInfoListener(OnInfoListener l) {
        onInfo = l;
    }

    public void setOnBufferingListener(OnBufferingListener l) {
        onBuffering = l;
    }

    private void releasePlayer() {
        generation++;
        main.removeCallbacks(openLatest);
        if (player != null) {
            final org.videolan.libvlc.MediaPlayer old = player;
            player = null;
            retiring = true;
            old.setEventListener(null);
            // Surface access stays on main; blocking native stop never occupies the remote/UI
            // thread.
            old.getVLCVout().detachViews();
            new Thread(
                            () -> {
                                try {
                                    old.stop();
                                } catch (RuntimeException e) {
                                    Log.w(
                                            "Android5Player",
                                            "STOP_FAILED " + e.getClass().getSimpleName());
                                } finally {
                                    main.post(
                                            () -> {
                                                old
                                                        .release(); // stopped already; also
                                                                    // unregisters Android UI/audio
                                                                    // callbacks
                                                retiring = false;
                                                queueOpen();
                                            });
                                }
                            },
                            "player-stop")
                    .start();
        }
        setKeepScreenOn(false);
    }

    public void onNewVideoLayout(
            IVLCVout v, int w, int h, int visibleW, int visibleH, int sn, int sd) {
        codedWidth = w;
        codedHeight = h;
        videoWidth = visibleW > 0 ? visibleW : w;
        videoHeight = visibleH > 0 ? visibleH : h;
        sarNum = sn > 0 ? sn : 1;
        sarDen = sd > 0 ? sd : 1;
        fitVideo();
    }

    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        fitVideo();
    }

    private void fitVideo() {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        if (player != null) {
            player.getVLCVout().setWindowSize(w, h);
        }
        if (videoWidth <= 0 || videoHeight <= 0) {
            // OpenGL vout reports zero layout dimensions and handles letterboxing
            // itself. Keep its surface and window in sync after every resize.
            surface.setLayoutParams(new LayoutParams(-1, -1, Gravity.CENTER));
            if (player != null) {
                player.setAspectRatio(null);
                player.setScale(0);
            }
            return;
        }
        float ratio = (float) videoWidth * sarNum / ((float) videoHeight * sarDen);
        if ((float) w / h > ratio) w = Math.round(h * ratio);
        else h = Math.round(w / ratio);
        // Account for codec padding just as LibVLC's VideoHelper does.
        w = (int) Math.ceil((double) w * Math.max(codedWidth, videoWidth) / videoWidth);
        h = (int) Math.ceil((double) h * Math.max(codedHeight, videoHeight) / videoHeight);
        surface.setLayoutParams(new LayoutParams(w, h, Gravity.CENTER));
    }

    protected void onDetachedFromWindow() {
        detached = true;
        stopPlayback();
        cache.close();
        onPrepared = null;
        onCompletion = null;
        onError = null;
        onInfo = null;
        onBuffering = null;
        pending = null;
        pendingHeaders = null;
        super.onDetachedFromWindow();
    }
}
