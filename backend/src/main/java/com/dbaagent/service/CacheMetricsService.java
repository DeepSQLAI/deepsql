package com.dbaagent.service;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CacheMetricsService {
    private final MeterRegistry meterRegistry;

    public void recordGet(String cacheName, boolean hit) {
        meterRegistry.counter(
            "cache.gets",
            "cache", cacheName,
            "result", hit ? "hit" : "miss"
        ).increment();
    }

    public void recordPut(String cacheName) {
        meterRegistry.counter("cache.puts", "cache", cacheName).increment();
    }

    public void recordEvict(String cacheName, long count) {
        if (count <= 0) {
            return;
        }
        meterRegistry.counter("cache.evictions", "cache", cacheName).increment(count);
    }
}
