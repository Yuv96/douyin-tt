package com.dycomment.tv;

/** One automatic appearance per selection, plus a temporary menu-owned appearance. */
final class InfoCardState {
    private boolean shown, menu;
    private long until;

    void selected() {
        shown = menu = false;
        until = 0;
    }

    void ready(long now) {
        if (shown) return;
        shown = true;
        until = now + 3000;
    }

    void menu(boolean open) {
        menu = open;
        // A menu visit consumes the automatic appearance, even if closed before 3 seconds.
        shown = true;
        until = 0;
    }

    boolean visible(long now) {
        return menu || remaining(now) > 0;
    }

    long remaining(long now) {
        return Math.max(0, until - now);
    }
}
