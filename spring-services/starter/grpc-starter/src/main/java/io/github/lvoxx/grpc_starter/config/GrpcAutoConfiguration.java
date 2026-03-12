package io.github.lvoxx.grpc_starter.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
public class GrpcAutoConfiguration {

    @Bean
    public GrpcAuthInterceptor grpcAuthInterceptor() {
        return new GrpcAuthInterceptor();
    }

    @Bean
    public GrpcTracingInterceptor grpcTracingInterceptor() {
        return new GrpcTracingInterceptor();
    }
}
