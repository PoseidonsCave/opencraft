package com.zenith.plugin.opencraft.ratelimit;

import com.zenith.plugin.opencraft.OpenCraftConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RateLimiterTest {

    private OpenCraftConfig   config;
    private RateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        config = new OpenCraftConfig();
        config.userCooldownMs          = 1000;
        config.userHourlyLimit         = 5;
        config.globalRequestsPerMinute = 100;
        config.maxConcurrentRequests   = 3;
        config.dailyBudgetTokens       = 0;
        rateLimiter = new RateLimiter(config);
    }

    @Test
    void firstRequest_allowed() {
        final RateLimitResult result = rateLimiter.check("user-1");
        assertTrue(result.allowed());
    }

    @Test
    void secondRequest_withinCooldown_denied() {
        rateLimiter.check("user-2");
        rateLimiter.recordRequest("user-2");
        final RateLimitResult result = rateLimiter.check("user-2");
        assertFalse(result.allowed(), "Second request within cooldown must be denied");
        assertTrue(result.retryAfterMs() > 0);
    }

    @Test
    void differentUsers_independentCooldowns() {
        rateLimiter.recordRequest("user-A");
        final RateLimitResult result = rateLimiter.check("user-B");
        assertTrue(result.allowed(), "Different users must have independent cooldowns");
    }

    @Test
    void hourlyLimit_exceeded_denied() {
        config.userCooldownMs = 0; // disable cooldown for this test
        rateLimiter = new RateLimiter(config);
        final String user = "user-limit";
        for (int i = 0; i < config.userHourlyLimit; i++) {
            rateLimiter.check(user);
            rateLimiter.recordRequest(user);
        }
        final RateLimitResult result = rateLimiter.check(user);
        assertFalse(result.allowed(), "Should be denied after exceeding hourly limit");
    }

    @Test
    void concurrencySlot_limitEnforced() {
        config.maxConcurrentRequests = 2;
        rateLimiter = new RateLimiter(config);
        assertTrue(rateLimiter.acquireConcurrencySlot());
        assertTrue(rateLimiter.acquireConcurrencySlot());
        assertFalse(rateLimiter.acquireConcurrencySlot(), "Third slot must not be available");
        rateLimiter.releaseConcurrencySlot();
        assertTrue(rateLimiter.acquireConcurrencySlot());
    }

    @Test
    void dailyBudget_disabled_noLimit() {
        config.dailyBudgetTokens = 0;
        rateLimiter = new RateLimiter(config);
        rateLimiter.recordTokens(Integer.MAX_VALUE);
        final RateLimitResult result = rateLimiter.check("user-3");
        assertTrue(result.allowed(), "Daily budget=0 must not limit requests");
    }

    @Test
    void dailyBudget_exceeded_denied() {
        config.dailyBudgetTokens = 100;
        config.userCooldownMs = 0;
        rateLimiter = new RateLimiter(config);
        rateLimiter.recordTokens(101);
        final RateLimitResult result = rateLimiter.check("user-4");
        assertFalse(result.allowed(), "Daily token budget exceeded must be denied");
    }
}
