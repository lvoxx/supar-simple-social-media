package io.github.lvoxx.media_service.service;

import org.springframework.http.codec.multipart.FilePart;
import reactor.core.publisher.Mono;

public interface StorageService {
    Mono<String> upload(FilePart file, String s3Key);

    Mono<Void> delete(String s3Key);

    String buildCdnUrl(String s3Key);
}
