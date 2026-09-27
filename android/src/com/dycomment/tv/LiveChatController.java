package com.dycomment.tv;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Read-only room subscription owned exclusively by the visible comments panel. */
public final class LiveChatController {
    private static final int TAG = 0x7f0f7a54;
    final Activity activity;
    String room = "", session = "";
    private LiveMessages.Listener listener;
    private boolean closed;
    private volatile int epoch;
    private int selection;
    private boolean running;
    private int failures;
    private LiveMessageTransport transport;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor work = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<Runnable>(1), new ThreadPoolExecutor.DiscardOldestPolicy());
    private final ArrayList<Chat> recent = new ArrayList<>();
    private final LinkedHashSet<String> seen = new LinkedHashSet<>();

    LiveChatController(Activity a) { activity = a; }

    static LiveChatController get(Activity a) {
        View decor = a.getWindow().getDecorView();
        Object old = decor.getTag(TAG);
        if (old instanceof LiveChatController) return (LiveChatController) old;
        LiveChatController controller = new LiveChatController(a);
        decor.setTag(TAG, controller);
        return controller;
    }

    public static void start(Activity a, String id) {
        LiveChatController controller = get(a);
        // Legacy playback hooks register selection; opening the reading panel starts requests.
        int selection = PlaybackCoordinator.token(a);
        String session = SocialApi.cookie();
        if (!controller.closed && controller.room.equals(id) && controller.selection == selection
                && controller.session.equals(session)) return;
        stop(a);
        if (id == null || !id.matches("[0-9]+") || controller.closed) return;
        controller.room = id;
        controller.session = session;
        controller.selection = selection;
    }

    public static void stop(Activity a) {
        LiveChatController controller = get(a);
        controller.epoch++;
        controller.running = false;
        controller.failures = 0;
        if (controller.transport != null) controller.transport.close();
        controller.transport = null;
        controller.work.getQueue().clear();
        controller.main.removeCallbacksAndMessages(null);
        controller.recent.clear();
        controller.seen.clear();
        controller.room = "";
        controller.session = "";
        controller.listener = null;
    }

    public static void destroy(Activity a) {
        stop(a);
        get(a).closed = true;
        get(a).work.shutdownNow();
        a.getWindow().getDecorView().setTag(TAG, null);
    }

    void subscribe(String id, LiveMessages.Listener observer) {
        start(activity, id);
        listener = observer;
        if (!closed && !room.isEmpty() && !session.isEmpty() && !running) {
            if (transport != null) transport.close();
            transport = new LiveMessageTransport(session);
            failures = 0;
            running = true;
            poll();
        }
        if (!recent.isEmpty()) observer.messages(new ArrayList<>(recent));
        else if (!running || failures > 0) observer.unavailable();
    }

    void unsubscribe(LiveMessages.Listener observer) {
        if (listener == observer) {
            stop(activity);
        }
    }

    private boolean valid(int token) {
        return !closed && running && listener != null && token == epoch && !activity.isFinishing()
                && !activity.isDestroyed() && session.equals(SocialApi.cookie())
                && PlaybackCoordinator.valid(activity, selection);
    }

    private void poll() {
        final int token = epoch;
        final LiveMessageTransport source = transport;
        final String selected = room;
        final android.util.DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
        work.execute(() -> {
            LiveMessageTransport.Batch batch = null;
            try {
                if (token != epoch) return;
                batch = source.next(selected, metrics.widthPixels, metrics.heightPixels);
            } catch (Exception unavailable) {
                // This feature never calls CredentialHealth or mutates the saved cookie.
            }
            final LiveMessageTransport.Batch result = batch;
            main.post(() -> {
                if (!valid(token)) {
                    if (token == epoch) stop(activity);
                    return;
                }
                if (result == null) {
                    failures++;
                    if (listener != null) listener.unavailable();
                    // HTML challenges, 403s and schema failures stop this subscription;
                    // no repeated background account traffic or rapid request retry.
                    running = false;
                    source.close();
                    return;
                }
                failures = 0;
                List<Chat> added = new ArrayList<>();
                for (Chat chat : result.messages) if (seen.add(chat.id)) {
                    if (seen.size() > 400) seen.remove(seen.iterator().next());
                    recent.add(chat);
                    if (recent.size() > 200) recent.remove(0);
                    added.add(chat);
                }
                if (listener != null && !added.isEmpty()) listener.messages(added);
                main.postDelayed(() -> { if (valid(token)) poll(); else if (token == epoch) stop(activity); },
                        result.interval);
            });
        });
    }

    static final class Chat {
        String id, text, author, content;
    }

    /** Webcast Response.messages_list -> WebcastChatMessage; ignores gifts and write actions. */
    static List<Chat> chats(Wire response) throws Exception {
        List<Chat> chats = new ArrayList<>();
        for (Object raw : response.all(1)) {
            if (!(raw instanceof byte[])) continue;
            Wire message = new Wire((byte[]) raw);
            if (!message.text(1).equals("WebcastChatMessage")) continue;
            Wire chat = message.child(2);
            String content = chat.text(3);
            if (content.isEmpty()) continue;
            Chat out = new Chat();
            out.id = Long.toString(message.number(3, chat.child(1).number(2, 0)));
            String author = chat.child(2).text(3);
            out.author = author.isEmpty() ? "抖音用户" : author;
            out.content = content.length() > 2000 ? content.substring(0, 2000) : content;
            out.text = (author.isEmpty() ? "" : author + "：") + content;
            if (out.text.length() > 200) out.text = out.text.substring(0, 200);
            if (out.id.equals("0")) out.id = out.text;
            chats.add(out);
            if (chats.size() >= 200) break;
        }
        return chats;
    }
}
