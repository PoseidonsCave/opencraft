package com.zenith.plugin.opencraft.ratelimit;

import com.zenith.plugin.opencraft.OpenCraftConfig;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class RateLimiter {

    private final OpenCraftConfig config;
    private final ConcurrentHashMap<String, Long>    lastRequestMs  = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, HourBucket> hourBuckets = new ConcurrentHashMap<>();
    private final AtomicInteger globalMinuteCount  = new AtomicInteger(0);
    private final AtomicLong    globalWindowStartMs = new AtomicLong(System.currentTimeMillis());
    private final Semaphore concurrencySlots;
    private final AtomicInteger dailyTokensUsed    = new AtomicInteger(0);
    private volatile long       dailyWindowStartMs = todayStartMs();

    public RateLimiter(final OpenCraftConfig config) {
        this.config = config;
        this.concurrencySlots = new Semaphore(Math.max(1, config.maxConcurrentRequests), true);
    }

        public RateLimitResult check(final String userKey) {
        final long now = System.currentTimeMillis();
        final Long last = lastRequestMs.get(userKey);
        if (last != null) {
            final long elapsed = now - last;
            if (elapsed < config.userCooldownMs) {
                final long wait = config.userCooldownMs - elapsed;
                return RateLimitResult.deny(
                    "Please wait " + (wait / 1000 + 1) + "s before sending another request.", wait);
            }
        }
        if (config.userHourlyLimit > 0) {
            final HourBucket bucket = hourBuckets.computeIfAbsent(userKey, k -> new HourBucket());
            if (!bucket.tryAcquire(now, config.userHourlyLimit)) {
                return RateLimitResult.deny("You have reached your hourly request limit.", 60_000L);
            }
        }
        if (config.globalRequestsPerMinute > 0) {
            synchronized (globalMinuteCount) {
                final long windowAge = now - globalWindowStartMs.get();
                if (windowAge >= 60_000L) {
                    globalMinuteCount.set(0);
                    globalWindowStartMs.set(now);
                }
                if (globalMinuteCount.get() >= config.globalRequestsPerMinute) {
                    return RateLimitResult.deny("The LLM assistant is busy. Please try again in a minute.", 10_000L);
                }
            }
        }
        if (config.dailyBudgetTokens > 0) {
            final long windowStart = dailyWindowStartMs;
            if (now >= windowStart + 86_400_000L) {
                final long newWindow = todayStartMs();
                if (DAILY_WINDOW_START.compareAndSet(this, windowStart, newWindow)) {
                    dailyTokensUsed.set(0);
                }
            }
            if (dailyTokensUsed.get() >= config.dailyBudgetTokens) {
                return RateLimitResult.deny("Daily token budget exhausted. Try again tomorrow.", 3_600_000L);
            }
        }

        return RateLimitResult.allow();
    }

        public void recordRequest(final String userKey) {
        lastRequestMs.put(userKey, System.currentTimeMillis());
        globalMinuteCount.incrementAndGet();
    }

        public void recordTokens(final int tokens) {
        if (tokens > 0) dailyTokensUsed.addAndGet(tokens);
    }

        public boolean acquireConcurrencySlot() {
        return concurrencySlots.tryAcquire();
    }

    public void releaseConcurrencySlot() {
        concurrencySlots.release();
    }

    private static final java.util.concurrent.atomic.AtomicLongFieldUpdater<RateLimiter> DAILY_WINDOW_START =
        java.util.concurrent.atomic.AtomicLongFieldUpdater.newUpdater(RateLimiter.class, "dailyWindowStartMs");

    private static long todayStartMs() {
        final long now = Instant.now().toEpochMilli();
        return now - (now % 86_400_000L);
    }

        private static final class HourBucket {
        private static final long WINDOW = 3_600_000L;
        private final long[] timestamps = new long[1000];
        private int head = 0;
        private int count = 0;

        synchronized boolean tryAcquire(final long now, final int limit) {
            final long cutoff = now - WINDOW;
            while (count > 0 && timestamps[(head - count + 1000) % 1000] < cutoff) {
                count--;
            }
            if (count >= limit) return false;
            timestamps[head] = now;
            head = (head + 1) % 1000;
            count++;
            return true;
        }
    }
}
