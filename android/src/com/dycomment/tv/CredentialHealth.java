package com.dycomment.tv;

import android.app.Activity;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import java.lang.ref.WeakReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/** Account health requires strict self-account evidence, never a failure of another feature. */
final class CredentialHealth {
    static final int UNKNOWN = 0, AUTHENTICATED = 1, UNAUTHENTICATED = 2, CHECK_SELF = 3;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService PROBES = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "account-health-probe"); thread.setDaemon(true); return thread;
    });
    private static WeakReference<Activity> host = new WeakReference<>(null);
    private static final State state = new State();
    private static final int BANNER = 0x7f0f7a58;

    static final class Ticket {
        final String session;
        final long epoch;
        Ticket(String session, long epoch) { this.session = session; this.epoch = epoch; }
    }
    /** Deterministic state also used by API21 fixtures; access is guarded by CredentialHealth.class. */
    static final class State {
        String session = "";
        long epoch, lastProbe = -30000;
        boolean invalid, running;
        void select(String current) {
            if (!session.equals(current)) { session = current; invalid = false; epoch++; }
        }
        void reset(String current) { session = current; invalid = false; epoch++; }
        boolean matches(String current, long observed) { return !current.isEmpty() && session.equals(current) && epoch == observed; }
        boolean apply(String current, long observed, int outcome) {
            if (!matches(current, observed) || (outcome != AUTHENTICATED && outcome != UNAUTHENTICATED)) return false;
            invalid = outcome == UNAUTHENTICATED; epoch++; return true;
        }
        Ticket begin(String current, long observed, long now) {
            if (!matches(current, observed) || running || now - lastProbe < 30000) return null;
            running = true; lastProbe = now; return new Ticket(current, observed);
        }
        boolean finish(Ticket ticket, int outcome) {
            running = false;
            return apply(ticket.session, ticket.epoch, outcome);
        }
    }

    static synchronized void reset() { state.reset(SocialApi.cookie()); MAIN.post(() -> render()); }
    static synchronized long observation(String session) {
        state.select(SocialApi.cookie());
        return state.session.equals(session) && !session.isEmpty() ? state.epoch : -1;
    }
    static synchronized void verified(String session, long observed) {
        state.select(SocialApi.cookie());
        if (state.apply(session, observed, AUTHENTICATED)) MAIN.post(() -> render());
    }

    static int evidence(String endpoint, int http, JSONObject result) {
        boolean self = OfficialLoginPolicy.SELF_PATH.equals(endpoint);
        boolean code8 = result != null && result.opt("status_code") instanceof Number
                && ((Number) result.opt("status_code")).doubleValue() == 8d;
        if (self) {
            if (http == 401 || (http == 200 && code8)) return UNAUTHENTICATED;
            if (http == 200 && result != null) {
                try { OfficialLoginPolicy.verified(result); return AUTHENTICATED; }
                catch (Exception inconclusive) { }
            }
            return UNKNOWN;
        }
        return http == 401 || http == 403 || (http == 200 && code8) ? CHECK_SELF : UNKNOWN;
    }

    static void observe(String endpoint, String session, long observed, int http, JSONObject result) {
        int outcome = evidence(endpoint, http, result);
        Ticket ticket = null;
        synchronized (CredentialHealth.class) {
            state.select(SocialApi.cookie());
            if (outcome == CHECK_SELF) ticket = state.begin(session, observed, SystemClock.elapsedRealtime());
            else if (state.apply(session, observed, outcome)) MAIN.post(() -> render());
        }
        if (ticket == null) return;
        final Ticket pending = ticket;
        try {
            PROBES.execute(() -> {
                int checked = SocialApi.probeAccount(pending.session);
                finishProbe(pending, checked);
            });
        } catch (RuntimeException unavailable) { finishProbe(pending, UNKNOWN); }
    }
    private static synchronized void finishProbe(Ticket ticket, int outcome) {
        state.select(SocialApi.cookie());
        if (state.finish(ticket, outcome)) MAIN.post(() -> render());
    }
    static synchronized boolean needsRefresh() { state.select(SocialApi.cookie()); return state.invalid; }

    static void attach(Activity a) { host = new WeakReference<>(a); render(); }
    static void detach(Activity a) {
        if (host.get() == a) { host.clear(); MAIN.removeCallbacksAndMessages(null); }
    }
    static void open(Activity a) { a.startActivity(new Intent(a, LanSyncActivity.class)); }
    private static void render() {
        Activity a = host.get();
        if (a == null || a.isFinishing() || a.isDestroyed()) return;
        ViewGroup decor = (ViewGroup) a.getWindow().getDecorView();
        View banner = decor.findViewById(BANNER);
        if (banner != null && banner.getParent() instanceof ViewGroup)
            ((ViewGroup) banner.getParent()).removeView(banner);
        InteractionController.updateClock(a);
    }
}
