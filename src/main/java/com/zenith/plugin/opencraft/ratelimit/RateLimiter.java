package com.zenith.plugin.opencraft.ratelimit;

import com.zenith.plugin.opencraft.OpenCraftConfig;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Multi-tier, thread-safe rate limiter.
 *
 * Tiers enforced (in order):
 * 
 *   - Max concurrent requests (semaphore).
 *   - Per-user cooldown (minimum ms between requests from the same user).
 *   - Per-user hourly limit.
 *   - Global requests-per-minute cap.
 * 
 *
 * Daily budget tracking is handled externally via #recordTokens(int).
 */
public final class RateLimiter {

    private final OpenCraftConfig config;

    // ── Per-user state ────────────────────────────────────────────────────────
    private final ConcurrentHashMap<String, Long>    lastRequestMs  = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, HourBucket> hourBuckets = new ConcurrentHashMap<>();

    // ── Global rate limit ─────────────────────────────────────────────────────
    private final AtomicInteger globalMinuteCount  = new AtomicInteger(0);
    private final AtomicLong    globalWindowStartMs = new AtomicLong(System.currentTimeMillis());

    // ── Concurrency ───────────────────────────────────────────────────────────
    private final Semaphore concurrencySlots;

    // ── Daily budget ──────────────────────────────────────────────────────────
    private final AtomicInteger dailyTokensUsed    = new AtomicInteger(0);
    private volatile long       dailyWindowStartMs = todayStartMs();

    public RateLimiter(final OpenCraftConfig config) {
        this.config = config;
        this.concurrencySlots = new Semaphore(Math.max(1, config.maxConcurrentRequests), true);
    }

    /**
     * Check all rate-limit tiers. Does NOT acquire the concurrency semaphore;
     * call acquireConcurrencySlot() separately before dispatch.
     * userKey: UUID string if available, otherwise username.
     */
    public RateLimitResult check(final String userKey) {
        final long now = System.currentTimeMillis();

        // ── Per-user cooldown ─────────────────────────────────────────────────
        final Long last = lastRequestMs.get(userKey);
        if (last != null) {
            final long elapsed = now - last;
            if (elapsed < config.userCooldownMs) {
                final long wait = config.userCooldownMs - elapsed;
                return RateLimitResult.deny(
                    "Please wait " + (wait / 1000 + 1) + "s before sending another request.", wait);
            }
        }

        // ── Per-user hourly limit ─────────────────────────────────────────────
        if (config.userHourlyLimit > 0) {
            final HourBucket bucket = hourBuckets.computeIfAbsent(userKey, k -> new HourBucket());
            if (!bucket.tryAcquire(now, config.userHourlyLimit)) {
                return RateLimitResult.deny("You have reached your hourly request limit.", 60_000L);
            }
        }

        // ── Global per-minute cap ─────────────────────────────────────────────
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

        // ── Daily budget ──────────────────────────────────────────────────────
        if (config.dailyBudgetTokens > 0) {
            // Snapshot the volatile once to avoid the read/compare/write race
            // where two threads both observe the old window, both reset, and
            // one's reset is silently overwritten.
            final long windowStart = dailyWindowStartMs;
            if (now >= windowStart + 86_400_000L) {
                final long newWindow = todayStartMs();
                // Only one thread wins the window swap; the loser's increment
                // will safely apply to the new window.
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

    /** Must be called after a request is allowed, before dispatch. */
    public void recordRequest(final String userKey) {
        lastRequestMs.put(userKey, System.currentTimeMillis());
        globalMinuteCount.incrementAndGet();
    }

    /** Record token usage for daily budget tracking. */
    public void recordTokens(final int tokens) {
        if (tokens > 0) dailyTokensUsed.addAndGet(tokens);
    }

    /**
     * Try to acquire a concurrency slot.
     * Return false immediately when all slots are busy.
     * Always call releaseConcurrencySlot in a finally block after success.
     */
    public boolean acquireConcurrencySlot() {
        return concurrencySlots.tryAcquire();
    }

    public void releaseConcurrencySlot() {
        concurrencySlots.release();
    }

    // ─────────────────────────────────────────────────────────────────────────

    private static final java.util.concurrent.atomic.AtomicLongFieldUpdater<RateLimiter> DAILY_WINDOW_START =
        java.util.concurrent.atomic.AtomicLongFieldUpdater.newUpdater(RateLimiter.class, "dailyWindowStartMs");

    private static long todayStartMs() {
        // Single read of the wall clock, then truncate to the start of the
        // current UTC day. The previous implementation called Instant.now()
        // twice and could land on different days at midnight rollover.
        final long now = Instant.now().toEpochMilli();
        return now - (now % 86_400_000L);
    }

    /** Sliding window counter for per-user hourly tracking. */
    private static final class HourBucket {
        private static final long WINDOW = 3_600_000L;
        private final long[] timestamps = new long[1000];
        private int head = 0;
        private int count = 0;

        synchronized boolean tryAcquire(final long now, final int limit) {
            // Evict old entries
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
