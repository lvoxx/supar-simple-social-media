package io.github.lvoxx.redis_starter.properties;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Data;

@Data
@ConfigurationProperties(prefix = "sssm.rate-limit")
public class RedisStarterProperties {

    private boolean enabled = true;
    private long defaultCapacity = 100;
    private long defaultRefillTokens = 100;
    private Duration defaultRefillPeriod = Duration.ofMinutes(1);
    private String keyPrefix = "sssm:rate-limit:";
}