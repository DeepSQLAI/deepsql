package com.dbaagent.config;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.cluster.ClusterClientOptions;
import io.lettuce.core.cluster.ClusterTopologyRefreshOptions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisClusterConfiguration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import java.time.Duration;
import java.util.List;

/**
 * Redis configuration for Azure Cache for Redis in cluster mode.
 * This provides proper SSL and cluster topology refresh settings.
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "spring.data.redis.cluster.nodes")
public class RedisConfig {

    @Value("${spring.data.redis.cluster.nodes:}")
    private List<String> clusterNodes;

    @Value("${spring.data.redis.password:}")
    private String password;

    @Value("${spring.data.redis.ssl.enabled:false}")
    private boolean sslEnabled;

    @Value("${spring.data.redis.timeout:2s}")
    private Duration timeout;

    @Bean
    public RedisConnectionFactory redisConnectionFactory() {
        log.info("Configuring Redis cluster connection to: {}", clusterNodes);

        // Cluster configuration
        RedisClusterConfiguration clusterConfig = new RedisClusterConfiguration(clusterNodes);
        if (password != null && !password.isEmpty()) {
            clusterConfig.setPassword(password);
        }

        // Cluster topology refresh options - important for Azure Redis
        ClusterTopologyRefreshOptions topologyRefreshOptions = ClusterTopologyRefreshOptions.builder()
            .enablePeriodicRefresh(Duration.ofMinutes(10))
            .enableAllAdaptiveRefreshTriggers()
            .build();

        // Cluster client options
        ClusterClientOptions clientOptions = ClusterClientOptions.builder()
            .topologyRefreshOptions(topologyRefreshOptions)
            .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
            .autoReconnect(true)
            .build();

        // Lettuce client configuration
        LettuceClientConfiguration.LettuceClientConfigurationBuilder clientConfigBuilder =
            LettuceClientConfiguration.builder()
                .commandTimeout(timeout)
                .clientOptions(clientOptions);

        // Enable SSL if configured
        if (sslEnabled) {
            clientConfigBuilder.useSsl().disablePeerVerification();
            log.info("Redis SSL enabled");
        }

        LettuceClientConfiguration clientConfig = clientConfigBuilder.build();

        LettuceConnectionFactory factory = new LettuceConnectionFactory(clusterConfig, clientConfig);
        factory.setValidateConnection(true);

        log.info("Redis cluster connection factory configured successfully");
        return factory;
    }
}
