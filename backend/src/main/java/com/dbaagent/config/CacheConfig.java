package com.dbaagent.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.databind.jsontype.PolymorphicTypeValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Configuration
@EnableCaching
public class CacheConfig implements CachingConfigurer {

    /**
     * Custom error handler that logs cache failures but doesn't throw exceptions.
     * This allows the application to continue working even when Redis is unavailable.
     */
    @Override
    public CacheErrorHandler errorHandler() {
        return new CacheErrorHandler() {
            @Override
            public void handleCacheGetError(RuntimeException exception, Cache cache, Object key) {
                log.warn("Cache GET failed for cache '{}', key '{}': {}",
                    cache.getName(), key, exception.getMessage());
            }

            @Override
            public void handleCachePutError(RuntimeException exception, Cache cache, Object key, Object value) {
                log.warn("Cache PUT failed for cache '{}', key '{}': {}",
                    cache.getName(), key, exception.getMessage());
            }

            @Override
            public void handleCacheEvictError(RuntimeException exception, Cache cache, Object key) {
                log.warn("Cache EVICT failed for cache '{}', key '{}': {}",
                    cache.getName(), key, exception.getMessage());
            }

            @Override
            public void handleCacheClearError(RuntimeException exception, Cache cache) {
                log.warn("Cache CLEAR failed for cache '{}': {}",
                    cache.getName(), exception.getMessage());
            }
        };
    }

    @Bean
    public RedisCacheManager redisCacheManager(
        RedisConnectionFactory connectionFactory,
        ObjectMapper objectMapper
    ) {
        ObjectMapper cacheMapper = objectMapper.copy();
        PolymorphicTypeValidator ptv = BasicPolymorphicTypeValidator.builder()
            .allowIfSubType("com.dbaagent.")
            .allowIfSubType("java.lang.")
            .allowIfSubType("java.math.")
            .allowIfSubType("java.time.")
            .allowIfSubType("java.util.")
            .build();
        cacheMapper.activateDefaultTyping(ptv, ObjectMapper.DefaultTyping.NON_FINAL, JsonTypeInfo.As.PROPERTY);

        RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
            .disableCachingNullValues()
            .entryTtl(Duration.ofMinutes(5))
            .serializeValuesWith(
                RedisSerializationContext.SerializationPair.fromSerializer(
                    new GenericJackson2JsonRedisSerializer(cacheMapper)
                )
            )
            .computePrefixWith(cacheName -> "dba-agent::" + cacheName + "::");

        Map<String, RedisCacheConfiguration> cacheConfigs = new HashMap<>();
        // Schema metadata is expensive to compute (N+1 queries), cache longer
        cacheConfigs.put("schemaMetadata", defaultConfig.entryTtl(Duration.ofMinutes(30)));
        cacheConfigs.put("databaseObjects", defaultConfig.entryTtl(Duration.ofMinutes(10)));
        // RAG retrieval involves embedding API calls, cache longer
        cacheConfigs.put("ragRetrieval", defaultConfig.entryTtl(Duration.ofMinutes(15)));
        cacheConfigs.put("explainAnalysis", defaultConfig.entryTtl(Duration.ofMinutes(10)));

        return RedisCacheManager.builder(connectionFactory)
            .cacheDefaults(defaultConfig)
            .withInitialCacheConfigurations(cacheConfigs)
            .build();
    }

    /**
     * Wraps the RedisCacheManager to handle connection failures gracefully.
     * If Redis is unavailable, getCache() returns null instead of throwing an exception.
     */
    @Bean
    @org.springframework.context.annotation.Primary
    public org.springframework.cache.CacheManager cacheManager(RedisCacheManager redisCacheManager) {
        return new org.springframework.cache.CacheManager() {
            @Override
            public Cache getCache(String name) {
                try {
                    return redisCacheManager.getCache(name);
                } catch (Exception e) {
                    log.warn("Failed to get cache '{}': {}", name, e.getMessage());
                    return null;
                }
            }

            @Override
            public java.util.Collection<String> getCacheNames() {
                try {
                    return redisCacheManager.getCacheNames();
                } catch (Exception e) {
                    log.warn("Failed to get cache names: {}", e.getMessage());
                    return java.util.Collections.emptyList();
                }
            }
        };
    }
}
