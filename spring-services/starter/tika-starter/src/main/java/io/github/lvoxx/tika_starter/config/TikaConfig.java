package io.github.lvoxx.tika_starter.config;

import org.apache.tika.Tika;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;

@Configuration
public class TikaConfig {

    @Bean
    @Scope
    public Tika tika() {
        return new Tika();
    }
}
