package io.github.lvoxx.media_service.service.impl;

import io.github.lvoxx.media_service.properties.S3Properties;
import io.github.lvoxx.media_service.service.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.nio.ByteBuffer;

@Slf4j
@Service
@RequiredArgsConstructor
public class S3StorageServiceImpl implements StorageService {

    private final S3AsyncClient s3;
    private final S3Properties props;

    @Override
    public Mono<String> upload(FilePart file, String s3Key) {
        return file.content()
                .map(dataBuffer -> {
                    byte[] bytes = new byte[dataBuffer.readableByteCount()];
                    dataBuffer.read(bytes);
                    return ByteBuffer.wrap(bytes);
                })
                .collectList()
                .flatMap(buffers -> {
                    long size = buffers.stream().mapToLong(ByteBuffer::remaining).sum();
                    PutObjectRequest req = PutObjectRequest.builder()
                            .bucket(props.getBucket())
                            .key(s3Key)
                            .contentType(file.headers().getContentType() != null
                                    ? file.headers().getContentType().toString()
                                    : "application/octet-stream")
                            .build();
                    AsyncRequestBody body = AsyncRequestBody.fromByteBuffers(buffers.toArray(ByteBuffer[]::new));
                    return Mono.fromFuture(s3.putObject(req, body))
                            .map(resp -> buildCdnUrl(s3Key));
                });
    }

    @Override
    public Mono<Void> delete(String s3Key) {
        DeleteObjectRequest req = DeleteObjectRequest.builder()
                .bucket(props.getBucket())
                .key(s3Key)
                .build();
        return Mono.fromFuture(s3.deleteObject(req))
                .doOnError(e -> log.error("Failed to delete S3 object {}: {}", s3Key, e.getMessage()))
                .then();
    }

    @Override
    public String buildCdnUrl(String s3Key) {
        return "https://" + props.getCloudfrontDomain() + "/" + s3Key;
    }
}
