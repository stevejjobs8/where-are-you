package io.github.stevejjobs8.whereareyou;

/** Monotonic elapsed-time rules for GPS fixes; no wall-clock timestamp assumptions. */
final class LocationFreshness {
    static final long MAX_FIX_AGE_MS = 60_000L;
    static final long GOOD_FIX_LAG_MS = 15_000L;

    static boolean isFresh(long nowMs, long fixMs) {
        return fixMs > 0 && fixMs <= nowMs && (nowMs - fixMs) <= MAX_FIX_AGE_MS;
    }

    /** Prefer an accurate (< 60m) fix only when it is not much older than the latest fix. */
    static boolean preferGood(long nowMs, long goodMs, long bestMs) {
        return isFresh(nowMs, goodMs)
                && (!isFresh(nowMs, bestMs) || goodMs >= bestMs - GOOD_FIX_LAG_MS);
    }

    static boolean isNewFix(long candidateMs, long publishedMs) {
        return candidateMs > publishedMs;
    }

    private LocationFreshness() {}
}
