package io.github.stevejjobs8.whereareyou;

import org.junit.Test;
import static org.junit.Assert.*;

public class LocationFreshnessTest {
    @Test public void acceptsRecentFix() {
        assertTrue(LocationFreshness.isFresh(101_000L, 100_000L));
        assertTrue(LocationFreshness.isFresh(160_000L, 100_000L));
    }
    @Test public void rejectsOldAndFutureFix() {
        assertFalse(LocationFreshness.isFresh(160_001L, 100_000L));
        assertFalse(LocationFreshness.isFresh(10_000L, 0L));
        assertFalse(LocationFreshness.isFresh(10_000L, 10_100L));
    }
    @Test public void prefersPreciseFixOnlyWithinTimeWindow() {
        assertTrue(LocationFreshness.preferGood(100_000L, 86_000L, 100_000L));
        assertFalse(LocationFreshness.preferGood(100_000L, 84_000L, 100_000L));
        assertFalse(LocationFreshness.preferGood(100_000L, 35_000L, 100_000L));
        assertTrue(LocationFreshness.preferGood(100_000L, 99_000L, 0L));
    }
    @Test public void doesNotRepublishSameFix() {
        assertTrue(LocationFreshness.isNewFix(99_000L, -1L));
        assertFalse(LocationFreshness.isNewFix(99_000L, 99_000L));
        assertFalse(LocationFreshness.isNewFix(98_999L, 99_000L));
        assertTrue(LocationFreshness.isNewFix(99_001L, 99_000L));
    }
}
