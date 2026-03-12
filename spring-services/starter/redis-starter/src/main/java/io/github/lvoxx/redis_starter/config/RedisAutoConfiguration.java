package io.github.lvoxx.redis_starter.config;

import org.redisson.api.RedissonClient;
import org.redisson.api.RedissonReactiveClient;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;

import io.github.lvoxx.redis_starter.properties.RedisStarterProperties;
import io.github.lvoxx.redis_starter.service.RateLimiterService;

@AutoConfiguration
@ConditionalOnClass(RedissonClient.class)
@EnableCaching
@EnableConfigurationProperties(RedisStarterProperties.class)
public class RedisAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(RedissonClient.class)
    public RedissonReactiveClient redissonReactiveClient(RedissonClient redissonClient) {
        return redissonClient.reactive();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "sssm.rate-limit", name = "enabled", havingValue = "true", matchIfMissing = true)
    public RateLimiterService rateLimiterService(
            RedissonReactiveClient redissonReactiveClient,
            RedisStarterProperties props) {
        return new RateLimiterService(
                redissonReactiveClient,
                props.getDefaultCapacity(),
                props.getDefaultRefillTokens(),
                props.getDefaultRefillPeriod().toSeconds());
    }
}