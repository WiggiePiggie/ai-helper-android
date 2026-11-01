package com.aihelper.extras;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;

/** Bounded, serial work whose pending values are replaced by their latest update. */
public final class CoalescingWorkQueue<K, V> {
    public enum Result { ENQUEUED, REPLACED, FULL, UNAVAILABLE }

    public interface Handler<V> {
        void process(V value);
    }

    public interface FailureHandler<V> {
        void failed(V value, RuntimeException error);
    }

    private final Object lock = new Object();
    private final int capacity;
    private final Executor executor;
    private final Handler<V> handler;
    private final FailureHandler<V> failureHandler;
    private final LinkedHashMap<K, V> pending = new LinkedHashMap<K, V>();
    private Drain worker;

    /** Capacity counts pending keys; a value currently being processed is separate. */
    public CoalescingWorkQueue(int capacity, Executor executor, Handler<V> handler,
                              FailureHandler<V> failureHandler) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
        this.executor = Objects.requireNonNull(executor, "executor");
        this.handler = Objects.requireNonNull(handler, "handler");
        this.failureHandler = Objects.requireNonNull(failureHandler, "failureHandler");
    }

    /**
     * Replacing a pending key keeps its original position. An active key can have
     * one pending follow-up. Callbacks and Executor.execute run outside the lock,
     * including when the executor runs submitted work synchronously.
     */
    public Result offer(K key, V value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        final Result result;
        final Drain scheduled;
        synchronized (lock) {
            if (pending.containsKey(key)) {
                pending.put(key, value);
                return Result.REPLACED;
            }
            if (pending.size() == capacity) return Result.FULL;
            pending.put(key, value);
            result = Result.ENQUEUED;
            if (worker == null) {
                scheduled = new Drain();
                worker = scheduled;
            } else {
                scheduled = null;
            }
        }
        if (scheduled != null) {
            try {
                executor.execute(scheduled);
            } catch (RuntimeException rejected) {
                List<V> discarded = null;
                synchronized (lock) {
                    // A rejected executor must not retain unscheduled requests.
                    // Ignore a stale rejection after a synchronous drain ended.
                    if (worker == scheduled && !scheduled.started) {
                        discarded = new ArrayList<V>(pending.values());
                        pending.clear();
                        worker = null;
                    }
                }
                if (discarded != null) for (V dropped : discarded) fail(dropped, rejected);
                return Result.UNAVAILABLE;
            }
        }
        return result;
    }

    private void fail(V value, RuntimeException error) {
        try {
            failureHandler.failed(value, error);
        } catch (RuntimeException ignored) {
            // A reporting failure must not strand later work.
        }
    }

    private final class Drain implements Runnable {
        private boolean started;

        public void run() {
            synchronized (lock) {
                // Some broken executors retain a rejected task. It may not drain
                // a later generation's work if it eventually runs.
                if (worker != this || started) return;
                started = true;
            }
            while (true) {
                final V value;
                synchronized (lock) {
                    if (pending.isEmpty()) {
                        worker = null;
                        return;
                    }
                    Iterator<Map.Entry<K, V>> first = pending.entrySet().iterator();
                    value = first.next().getValue();
                    first.remove();
                }
                try {
                    handler.process(value);
                } catch (RuntimeException error) {
                    fail(value, error);
                }
            }
        }
    }
}
