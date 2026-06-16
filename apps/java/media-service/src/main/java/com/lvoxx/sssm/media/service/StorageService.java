package com.lvoxx.sssm.media.service;

import com.lvoxx.sssm.media.config.StorageProperties;
import com.lvoxx.sssm.media.error.NotFoundException;
import java.time.Instant;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;

/**
 * Thin wrapper over the R2 (S3) object operations media-service needs: minting a presigned PUT URL
 * for the client's direct upload, HEAD-verifying that the upload landed, and deleting the original.
 * Variant transcoding is imgproxy's job, not this service's, so there is no GET/transform here.
 */
@Service
public class StorageService {

    private final S3Client s3;
    private final S3Presigner presigner;
    private final StorageProperties props;

    public StorageService(S3Client s3, S3Presigner presigner, StorageProperties props) {
        this.s3 = s3;
        this.presigner = presigner;
        this.props = props;
    }

    /** Mints a presigned PUT URL the client uploads the original bytes to, directly to R2. */
    public PresignedUpload presignUpload(String objectKey, String contentType) {
        PutObjectRequest put = PutObjectRequest.builder()
                .bucket(props.bucket())
                .key(objectKey)
                .contentType(contentType)
                .build();
        PresignedPutObjectRequest presigned = presigner.presignPutObject(b -> b
                .signatureDuration(props.presignTtl())
                .putObjectRequest(put));
        return new PresignedUpload(presigned.url().toString(), Instant.now().plus(props.presignTtl()));
    }

    /**
     * HEADs the stored object to confirm the client's upload actually landed and to read its real
     * size/content-type. A missing object means the client never completed the upload.
     *
     * @throws NotFoundException if no object exists at {@code objectKey}
     */
    public HeadResult head(String objectKey) {
        try {
            HeadObjectResponse r = s3.headObject(b -> b.bucket(props.bucket()).key(objectKey));
            return new HeadResult(r.contentType(), r.contentLength());
        } catch (NoSuchKeyException e) {
            throw new NotFoundException("No uploaded object found at " + objectKey);
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                throw new NotFoundException("No uploaded object found at " + objectKey);
            }
            throw e;
        }
    }

    /** Removes the original object from R2. Safe to call even if the object is already gone. */
    public void delete(String objectKey) {
        s3.deleteObject(b -> b.bucket(props.bucket()).key(objectKey));
    }

    /** A presigned upload URL and the instant it stops being accepted by R2. */
    public record PresignedUpload(String url, Instant expiresAt) {
    }

    /** The bits of an R2 HEAD response media-service records on completion. */
    public record HeadResult(String contentType, Long contentLength) {
    }
}
