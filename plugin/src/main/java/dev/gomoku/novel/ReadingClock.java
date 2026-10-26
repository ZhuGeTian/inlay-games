/*
 * Adapted from datouggg/missNovel (commit f94dcef71b9f98e05993507e3453fba562aab642).
 * Licensed under Apache License 2.0; see META-INF/licenses/missNovel-Apache-2.0.txt.
 * Modified for Inlay Games: isolated namespace/storage, Java 17 baseline and shared controls.
 */
package dev.gomoku.novel;

final class ReadingClock {
    private long previous;
    private long elapsed;
    private boolean wasEligible;

    boolean tick(long now, boolean eligible, int intervalSeconds) {
        // A suspended IDE must not consume unread pages when it wakes up.
        if (eligible && wasEligible) elapsed += Math.max(0, Math.min(now - previous, 500_000_000L));
        previous = now;
        wasEligible = eligible;
        if (elapsed < intervalSeconds * 1_000_000_000L) return false;
        elapsed = 0;
        return true;
    }

    void reset() { elapsed = 0; wasEligible = false; }
}
