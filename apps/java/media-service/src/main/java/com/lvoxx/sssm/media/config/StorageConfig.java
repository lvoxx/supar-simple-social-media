package com.lvoxx.sssm.media.config;

import java.net.URI;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * Builds the S3 SDK clients pointed at Cloudflare R2. The sync {@link S3Client} runs HEAD/DELETE on
 * stored originals; the {@link S3Presigner} mints the presigned PUT URLs clients upload to directly.
 * Both use path-style addressing (required by R2) and static R2 token credentials. Clients are built
 * lazily and open no connection at startup, so the context loads even with placeholder dev settings.
 */
@Configuration
@EnableConfigurationProperties({StorageProperties.class, ImgproxyProperties.class})
public class StorageConfig {

    private static final S3Configuration PATH_STYLE =
            S3Configuration.builder().pathStyleAccessEnabled(true).build();

    @Bean
    public S3Client s3Client(StorageProperties props) {
        return S3Client.builder()
                .endpointOverride(URI.create(props.endpoint()))
                .region(Region.of(props.region()))
                .credentialsProvider(credentials(props))
                .httpClient(UrlConnectionHttpClient.create())
                .serviceConfiguration(PATH_STYLE)
                .build();
    }

    @Bean
    public S3Presigner s3Presigner(StorageProperties props) {
        return S3Presigner.builder()
                .endpointOverride(URI.create(props.endpoint()))
                .region(Region.of(props.region()))
                .credentialsProvider(credentials(props))
                .serviceConfiguration(PATH_STYLE)
                .build();
    }

    private static StaticCredentialsProvider credentials(StorageProperties props) {
        return StaticCredentialsProvider.create(
                AwsBasicCredentials.create(props.accessKey(), props.secretKey()));
    }
}
