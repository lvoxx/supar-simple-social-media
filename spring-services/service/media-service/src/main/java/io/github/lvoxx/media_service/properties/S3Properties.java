package io.github.lvoxx.media_service.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "aws.s3")
public class S3Properties {
    private String bucket;
    private String region = "us-east-1";
    private String cloudfrontDomain;
    private String defaultPrefix = "sssm/media";
}
