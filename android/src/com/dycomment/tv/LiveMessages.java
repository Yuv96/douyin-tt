package com.dycomment.tv;

import android.app.Activity;
import java.util.List;

/** The reading panel owns the room stream; closing it cancels requests and clears retained data. */
final class LiveMessages {
    interface Listener {
        void messages(List<LiveChatController.Chat> messages);
        void unavailable();
    }

    interface Source {
        boolean current();
        void start(Listener listener);
        void close();
    }

    static Source remote(Activity activity, String room) {
        final String cookie = SocialApi.cookie();
        final int selection = PlaybackCoordinator.token(activity);
        final LiveChatController stream = LiveChatController.get(activity);
        return new Source() {
            private Listener listener;

            public boolean current() {
                return cookie.equals(SocialApi.cookie())
                        && PlaybackCoordinator.valid(activity, selection)
                        && (stream.room.isEmpty() || room.equals(stream.room));
            }

            public void start(Listener value) {
                listener = value;
                if (current()) stream.subscribe(room, value);
            }

            public void close() {
                if (listener != null) stream.unsubscribe(listener);
                listener = null;
            }
        };
    }
}
