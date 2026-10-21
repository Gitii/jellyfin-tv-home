package com.gathin.tvhome;

import java.util.Iterator;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Reserve slots for visible work; speculative work can always be displaced without failing a tile. */
final class JellyfinImageQueue {
    static final int CAPACITY = 24;
    static final int PREFETCH = 0, VISIBLE = 1, FOCUSED = 2;

    private JellyfinImageQueue() { }

    static <T> boolean offer(LinkedBlockingDeque<T> queue, T task, int priority,
            Predicate<T> speculative, Consumer<T> dropped) {
        int limit = priority == PREFETCH ? CAPACITY - 8 : priority == VISIBLE ? CAPACITY - 1 : CAPACITY;
        while (queue.size() >= limit) {
            T evicted = null;
            for (Iterator<T> it = queue.descendingIterator(); it.hasNext();) {
                T candidate = it.next();
                if (speculative.test(candidate) && queue.remove(candidate)) { evicted = candidate; break; }
            }
            if (evicted == null) return false;
            dropped.accept(evicted);
        }
        return priority == FOCUSED ? queue.offerFirst(task) : queue.offerLast(task);
    }
}
