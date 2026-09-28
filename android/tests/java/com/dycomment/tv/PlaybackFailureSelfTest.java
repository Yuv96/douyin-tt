package com.dycomment.tv;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Deterministic retry/removal policy fixtures; native playback is covered by SwitchingSelfTestActivity. */
final class PlaybackFailureSelfTest {
    private static void require(boolean condition, String detail) {
        if (!condition) throw new IllegalStateException("playback failure: " + detail);
    }

    static void run() {
        Object first = new Object(), second = new Object(), third = new Object();
        PlaybackCoordinator.FailureBudget budget = new PlaybackCoordinator.FailureBudget();
        budget.select(first, 1);
        require(budget.error(1) == PlaybackCoordinator.FailureBudget.RETRY, "first failure grants one retry");
        require(budget.error(1) == PlaybackCoordinator.FailureBudget.IGNORE, "duplicate error is consumed once");
        budget.select(first, 2);
        require(budget.error(1) == PlaybackCoordinator.FailureBudget.IGNORE, "old attempt cannot spend the retry budget");
        require(budget.error(2) == PlaybackCoordinator.FailureBudget.REMOVE, "same-object playAt retains the spent retry");
        require(budget.error(2) == PlaybackCoordinator.FailureBudget.IGNORE, "second-attempt duplicate cannot remove twice");
        budget.select(first, 3);
        require(budget.error(3) == PlaybackCoordinator.FailureBudget.REMOVE, "another same-object selection does not restore retry allowance");
        budget.select(second, 4);
        require(budget.error(3) == PlaybackCoordinator.FailureBudget.IGNORE, "previous selection callback is stale");
        require(budget.error(4) == PlaybackCoordinator.FailureBudget.RETRY, "successor has its own one-retry allowance");
        budget.select(null, 5);
        require(budget.error(5) == PlaybackCoordinator.FailureBudget.IGNORE, "empty or stopped selection ignores errors");

        budget.select(first, 6);
        require(budget.beginDetail(6) && !budget.beginDetail(6), "one detail request per playback attempt");
        require(!budget.detailResult(5) && budget.detailResult(6) && !budget.detailResult(6),
                "only one current detail callback may start playback");
        require(budget.error(6) == PlaybackCoordinator.FailureBudget.RETRY && !budget.beginDetail(6),
                "failed detail cannot layer another request onto the consumed attempt");
        budget.select(first, 7);
        require(budget.beginDetail(7) && !budget.detailResult(6) && budget.detailResult(7),
                "retry accepts its own detail result and rejects the stale result");
        require(budget.error(7) == PlaybackCoordinator.FailureBudget.REMOVE,
                "detail failure shares the same two-attempt budget as native failure");
        budget.select(null, 8);

        List<Object> feed = new ArrayList<>(Arrays.asList(first, second, third));
        require(PlaybackCoordinator.removeFailed(feed, 0, second) == -1 && feed.size() == 3,
                "stale object cannot remove a different current entry");
        require(PlaybackCoordinator.removeFailed(feed, -1, first) == -1 && feed.size() == 3,
                "negative current index is harmless");
        require(PlaybackCoordinator.removeFailed(feed, 3, first) == -1 && feed.size() == 3,
                "index beyond the list is harmless");
        require(PlaybackCoordinator.removeFailed(feed, 1, second) == 1 && feed.get(1) == third,
                "removal plays the successor now occupying the same index");
        require(PlaybackCoordinator.removeFailed(feed, 1, third) == -1 && feed.size() == 1 && feed.get(0) == first,
                "failed tail stops instead of wrapping to an earlier item");
        require(PlaybackCoordinator.removeFailed(feed, 0, first) == -1 && feed.isEmpty(),
                "the last item leaves an empty list without indexing it");

        feed.addAll(Arrays.asList(first, second, third));
        int epoch = 10, attempts = 0, index = 0;
        while (index >= 0 && attempts < 7) {
            Object item = feed.get(index);
            budget.select(item, epoch++); attempts++;
            require(budget.error(epoch - 1) == PlaybackCoordinator.FailureBudget.RETRY, "consecutive item initial failure");
            budget.select(item, epoch++); attempts++;
            require(budget.error(epoch - 1) == PlaybackCoordinator.FailureBudget.REMOVE, "consecutive item exhausts retry");
            index = PlaybackCoordinator.removeFailed(feed, index, item);
        }
        require(feed.isEmpty() && attempts == 6 && index == -1, "three failed entries terminate after exactly six attempts");
        android.util.Log.i("Android5InteractionTest", "PLAYBACK_ONE_RETRY_REMOVE_STALE_GUARD_OK");
    }
}
