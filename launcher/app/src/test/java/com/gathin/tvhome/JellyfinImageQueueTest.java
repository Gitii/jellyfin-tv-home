package com.gathin.tvhome;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingDeque;
import static org.junit.Assert.*;

public class JellyfinImageQueueTest {
    @Test public void prefetchLeavesReservedCapacityForVisibleWork() {
        LinkedBlockingDeque<Integer> queue = new LinkedBlockingDeque<>(JellyfinImageQueue.CAPACITY);
        for (int i = 0; i < 40; i++) {
            JellyfinImageQueue.offer(queue, i, JellyfinImageQueue.PREFETCH, item -> true, item -> { });
        }
        assertEquals(16, queue.size());
    }

    @Test public void focusedRequestEvictsSpeculationFromSaturatedQueue() {
        LinkedBlockingDeque<Integer> queue = new LinkedBlockingDeque<>(JellyfinImageQueue.CAPACITY);
        for (int i = 0; i < 24; i++) queue.add(i);
        List<Integer> dropped = new ArrayList<>();
        assertTrue(JellyfinImageQueue.offer(queue, 100, JellyfinImageQueue.FOCUSED, item -> item < 24, dropped::add));
        assertEquals(Integer.valueOf(100), queue.peekFirst());
        assertEquals(24, queue.size());
        assertEquals(1, dropped.size());
    }

    @Test public void visibleRequestsLeaveOneSlotForFocusedArtwork() {
        LinkedBlockingDeque<Integer> queue = new LinkedBlockingDeque<>(JellyfinImageQueue.CAPACITY);
        for (int i = 0; i < 23; i++) {
            assertTrue(JellyfinImageQueue.offer(queue, i, JellyfinImageQueue.VISIBLE, item -> false, item -> fail()));
        }
        assertTrue(JellyfinImageQueue.offer(queue, 100, JellyfinImageQueue.FOCUSED, item -> false, item -> fail()));
        assertEquals(Integer.valueOf(100), queue.peekFirst());
    }

    @Test public void deferredThumbnailCanBeAdmittedWhenCapacityReturns() {
        LinkedBlockingDeque<Integer> queue = new LinkedBlockingDeque<>(JellyfinImageQueue.CAPACITY);
        for (int i = 0; i < 23; i++) queue.add(i);
        assertFalse(JellyfinImageQueue.offer(queue, 100, JellyfinImageQueue.VISIBLE, item -> false, item -> fail()));
        queue.pollFirst();
        assertTrue(JellyfinImageQueue.offer(queue, 100, JellyfinImageQueue.VISIBLE, item -> false, item -> fail()));
        assertTrue(queue.contains(100));
    }
}
