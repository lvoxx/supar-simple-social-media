# media-service

**Type:** Spring Boot · Port `8082`  
**Primary DB:** PostgreSQL (R2DBC) — schema `sssm_media`  
**Cache:** Redis  
**Object Storage:** AWS S3  
**CDN:** AWS CloudFront  
**Starters:** `postgres-starter` `redis-starter` `kafka-starter` `metrics-starter` `security-starter` `grpc-starter`

---

## Responsibilities

Centralised media management. Accepts uploads, validates content (Tika magic-byte detection), compresses/transcodes (libvips / FFmpeg), delegates safety scan tới media-guard-service, lưu processed file lên **AWS S3**, phục vụ qua **AWS CloudFront**, và thông báo caller qua Kafka.

Cloudinary được loại bỏ hoàn toàn. S3 là single source of truth cho binary files; CloudFront là CDN distribution layer trước S3.

---

## Why S3 + CloudFront thay Cloudinary

| Tiêu chí            | Cloudinary                                         | AWS S3 + CloudFront                                                             |
| ------------------- | -------------------------------------------------- | ------------------------------------------------------------------------------- |
| Kiến trúc nhất quán | Vendor ngoài                                       | Hoàn toàn trong AWS account đã có (VPC, EKS, RDS, MSK đều trên AWS)             |
| Chi phí ở scale     | Pricing per transformation tốn kém khi traffic lớn | Storage + transfer thấp hơn; transcode tự quản lý (tiết kiệm 70–90%)            |
| IAM / security      | API key riêng, không tích hợp với IAM              | IRSA (IAM Role for Service Account) — không cần credential secrets              |
| Latency             | Cloudinary CDN (edge ngoài region)                 | CloudFront có edge tại cùng region với EKS                                      |
| Transformation      | Cloudinary URL transform                           | Thực hiện tại upload time (libvips/FFmpeg trong Pod) — kết quả lưu thẳng vào S3 |
| Availability        | SLA phụ thuộc Cloudinary                           | S3 SLA 99.99%, CloudFront SLA 99.9%                                             |

---

## DB init

K8S `Job` runs Flyway CLI.  
Scripts: `infrastructure/k8s/db-init/media-service/sql/V1__init_media_assets.sql`

---

## Schema (schema `sssm_media`)

```sql
-- media_assets
id                   UUID        PRIMARY KEY DEFAULT gen_random_uuid()
owner_id             UUID        NOT NULL
owner_type           VARCHAR(20) NOT NULL     -- USER | POST | COMMENT | MESSAGE | GROUP
original_filename    TEXT
content_type         VARCHAR(100)             -- detected by Tika, không tin client header
file_size_bytes      BIGINT                   -- raw upload size
processed_size_bytes BIGINT                   -- sau compression/transcode
s3_key               TEXT        NOT NULL     -- full S3 object key, e.g. images/2025/06/mediaId.webp
s3_bucket            TEXT        NOT NULL     -- bucket name (có thể khác nhau dev/prod)
cdn_url              TEXT        NOT NULL     -- CloudFront URL: https://cdn.example.com/{s3_key}
thumbnail_s3_key     TEXT                     -- NULL cho audio/pdf
thumbnail_url        TEXT                     -- CloudFront URL của thumbnail
width                INT
height               INT
duration_seconds     INT
status               VARCHAR(20) NOT NULL DEFAULT 'PROCESSING'
                                              -- PROCESSING | READY | REJECTED | DELETED
rejection_reason     TEXT
created_at           TIMESTAMPTZ NOT NULL DEFAULT NOW()
updated_at           TIMESTAMPTZ
created_by           UUID
updated_by           UUID
is_deleted           BOOLEAN     NOT NULL DEFAULT FALSE
deleted_at           TIMESTAMPTZ
deleted_by           UUID
```

> **Lưu ý:** `cloudinary_public_id`, `cloudinary_url` đã được loại bỏ hoàn toàn. `cdn_url` bây giờ là CloudFront URL, `s3_key` là vị trí lưu trữ thực tế.

---

## AWS Infrastructure Integration

### S3 Bucket layout

```
s3://sssm-media-{env}/           (env = prod | staging | dev)
├── images/
│   ├── avatars/         {mediaId}.webp
│   ├── posts/           {mediaId}.webp
│   ├── posts/thumbs/    {mediaId}_thumb.webp
│   ├── groups/          {mediaId}.webp
│   └── comments/        {mediaId}.webp
├── videos/
│   ├── posts/           {mediaId}.mp4
│   └── posts/thumbs/    {mediaId}_thumb.jpg
├── audio/
│   └── messages/        {mediaId}.aac
└── attachments/
    └── messages/        {mediaId}.pdf
```

### S3 Bucket policy (Terraform managed)

```hcl
# terraform/modules/s3/media.tf

resource "aws_s3_bucket" "media" {
  bucket = "sssm-media-${var.env}"
}

resource "aws_s3_bucket_public_access_block" "media" {
  bucket                  = aws_s3_bucket.media.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
  # Không public trực tiếp — chỉ serve qua CloudFront OAC
}

# CloudFront Origin Access Control
resource "aws_cloudfront_origin_access_control" "media" {
  name                              = "sssm-media-oac"
  origin_access_control_origin_type = "s3"
  signing_behavior                  = "always"
  signing_protocol                  = "sigv4"
}

# Bucket policy: chỉ cho phép CloudFront OAC truy cập
resource "aws_s3_bucket_policy" "media" {
  bucket = aws_s3_bucket.media.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid       = "AllowCloudFrontOAC"
      Effect    = "Allow"
      Principal = { Service = "cloudfront.amazonaws.com" }
      Action    = "s3:GetObject"
      Resource  = "${aws_s3_bucket.media.arn}/*"
      Condition = {
        StringEquals = {
          "AWS:SourceArn" = aws_cloudfront_distribution.media.arn
        }
      }
    }]
  })
}

# IRSA: media-service Pod được phép PutObject, DeleteObject (không GetObject — dùng CloudFront)
resource "aws_iam_role" "media_service" {
  name = "sssm-media-service-${var.env}"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Federated = var.oidc_provider_arn }
      Action    = "sts:AssumeRoleWithWebIdentity"
      Condition = {
        StringEquals = {
          "${var.oidc_provider}:sub" = "system:serviceaccount:sssm:media-service"
        }
      }
    }]
  })
}

resource "aws_iam_role_policy" "media_service_s3" {
  role = aws_iam_role.media_service.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect   = "Allow"
        Action   = ["s3:PutObject", "s3:DeleteObject"]
        Resource = "${aws_s3_bucket.media.arn}/*"
      },
      {
        Effect   = "Allow"
        Action   = "s3:HeadObject"
        Resource = "${aws_s3_bucket.media.arn}/*"
      }
    ]
  })
}
```

### CloudFront Distribution (Terraform managed)

```hcl
# terraform/modules/s3/cloudfront.tf

resource "aws_cloudfront_distribution" "media" {
  enabled             = true
  price_class         = "PriceClass_All"
  aliases             = ["cdn.${var.domain}"]   # e.g. cdn.sssm.io
  default_root_object = ""

  origin {
    domain_name              = aws_s3_bucket.media.bucket_regional_domain_name
    origin_id                = "s3-sssm-media"
    origin_access_control_id = aws_cloudfront_origin_access_control.media.id
  }

  default_cache_behavior {
    target_origin_id       = "s3-sssm-media"
    viewer_protocol_policy = "redirect-to-https"
    allowed_methods        = ["GET", "HEAD"]
    cached_methods         = ["GET", "HEAD"]
    compress               = true

    cache_policy_id = aws_cloudfront_cache_policy.media.id
  }

  viewer_certificate {
    acm_certificate_arn      = var.acm_certificate_arn
    ssl_support_method       = "sni-only"
    minimum_protocol_version = "TLSv1.2_2021"
  }

  restrictions {
    geo_restriction { restriction_type = "none" }
  }
}

resource "aws_cloudfront_cache_policy" "media" {
  name        = "sssm-media-cache"
  default_ttl = 86400       # 1 ngày
  max_ttl     = 31536000    # 1 năm
  min_ttl     = 0

  parameters_in_cache_key_and_forwarded_to_origin {
    cookies_config  { cookie_behavior  = "none" }
    headers_config  { header_behavior  = "none" }
    query_strings_config { query_string_behavior = "none" }
    enable_accept_encoding_brotli = true
    enable_accept_encoding_gzip   = true
  }
}
```

---

## IRSA — Không cần AWS credentials trong Pod

```yaml
# helm/charts/media-service/templates/serviceaccount.yaml
apiVersion: v1
kind: ServiceAccount
metadata:
  name: media-service
  namespace: sssm
  annotations:
    eks.amazonaws.com/role-arn: arn:aws:iam::${AWS_ACCOUNT_ID}:role/sssm-media-service-prod
```

AWS SDK trong Pod tự lấy credentials từ IRSA token — không có `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY` trong environment.

---

## File type detection — Apache Tika

**Dependency:** `org.apache.tika:tika-core` (không dùng `tika-parsers`)

```java
@Component
public class TikaFileTypeDetector {

    private static final Tika TIKA = new Tika();

    private static final Set<String> ALLOWED_MIME_TYPES = Set.of(
        "image/jpeg", "image/png", "image/gif", "image/webp", "image/heic", "image/heif",
        "video/mp4", "video/quicktime", "video/x-msvideo", "video/webm", "video/x-matroska",
        "audio/mpeg", "audio/mp4", "audio/ogg", "audio/flac", "audio/wav", "audio/aac",
        "application/pdf"
    );

    public Mono<String> detectFromFlux(String filename, Flux<DataBuffer> content) {
        return content
            .take(1)
            .next()
            .map(buffer -> {
                try (InputStream is = new BufferedInputStream(buffer.asInputStream())) {
                    Metadata metadata = new Metadata();
                    metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, filename);
                    String mimeType = TIKA.detect(is, metadata);
                    if (!ALLOWED_MIME_TYPES.contains(mimeType)) {
                        throw new UnsupportedMediaTypeException(
                            "File type not supported: " + mimeType);
                    }
                    return mimeType;
                } catch (IOException e) {
                    throw new MediaValidationException("Failed to detect file type", e);
                } finally {
                    DataBufferUtils.release(buffer);
                }
            });
    }
}
```

---

## S3 Upload Service

**SDK:** `software.amazon.awssdk:s3` (AWS SDK v2) + `software.amazon.awssdk:s3-transfer-manager`  
Credentials được cung cấp tự động qua IRSA — không cần config `accessKey`/`secretKey`.

### S3MediaStorageService

```java
@Service
@RequiredArgsConstructor
public class S3MediaStorageService {

    private final S3AsyncClient      s3Client;         // V2 async client (non-blocking)
    private final S3TransferManager  transferManager;
    private final S3Properties       props;

    /**
     * Upload processed bytes lên S3.
     * Dùng S3TransferManager cho multipart upload tự động với file > 8 MB.
     *
     * @param mediaId   dùng làm phần của S3 key — idempotent khi retry
     * @param bytes     processed content (post-compression/transcode)
     * @param mimeType  detected MIME type
     * @param ownerType xác định sub-folder
     * @return S3UploadResult chứa s3Key và cdnUrl
     */
    public Mono<S3UploadResult> upload(String mediaId, byte[] bytes,
                                       String mimeType, MediaOwnerType ownerType) {
        String s3Key   = buildS3Key(mediaId, mimeType, ownerType, false);
        String cdnUrl  = props.getCdnBaseUrl() + "/" + s3Key;

        return Mono.fromFuture(() ->
            s3Client.putObject(
                PutObjectRequest.builder()
                    .bucket(props.getBucket())
                    .key(s3Key)
                    .contentType(mimeType)
                    .contentLength((long) bytes.length)
                    // Cache-Control: immutable — mediaId duy nhất, không bao giờ overwrite
                    .cacheControl("public, max-age=31536000, immutable")
                    .metadata(Map.of("mediaId", mediaId, "ownerType", ownerType.name()))
                    .build(),
                AsyncRequestBody.fromBytes(bytes)
            )
        )
        .retryWhen(Retry.backoff(3, Duration.ofSeconds(2))
            .filter(ex -> ex instanceof SdkException))
        .map(resp -> S3UploadResult.builder()
            .s3Key(s3Key)
            .s3Bucket(props.getBucket())
            .cdnUrl(cdnUrl)
            .bytes((long) bytes.length)
            .build());
    }

    /**
     * Upload thumbnail riêng (JPEG thumbnail của video hoặc image post).
     */
    public Mono<S3UploadResult> uploadThumbnail(String mediaId, byte[] thumbBytes,
                                                 MediaOwnerType ownerType) {
        String s3Key  = buildS3Key(mediaId, "image/jpeg", ownerType, true);
        String cdnUrl = props.getCdnBaseUrl() + "/" + s3Key;

        return Mono.fromFuture(() ->
            s3Client.putObject(
                PutObjectRequest.builder()
                    .bucket(props.getBucket())
                    .key(s3Key)
                    .contentType("image/jpeg")
                    .contentLength((long) thumbBytes.length)
                    .cacheControl("public, max-age=31536000, immutable")
                    .build(),
                AsyncRequestBody.fromBytes(thumbBytes)
            )
        )
        .map(resp -> S3UploadResult.builder()
            .s3Key(s3Key)
            .s3Bucket(props.getBucket())
            .cdnUrl(cdnUrl)
            .build());
    }

    /**
     * Soft-delete: xoá object khỏi S3 khi media bị xoá.
     * Chỉ gọi sau khi PostgreSQL record đã được đánh is_deleted=TRUE.
     */
    public Mono<Void> delete(String s3Key, String thumbnailS3Key) {
        List<ObjectIdentifier> objects = new ArrayList<>();
        objects.add(ObjectIdentifier.builder().key(s3Key).build());
        if (thumbnailS3Key != null) {
            objects.add(ObjectIdentifier.builder().key(thumbnailS3Key).build());
        }

        return Mono.fromFuture(() ->
            s3Client.deleteObjects(
                DeleteObjectsRequest.builder()
                    .bucket(props.getBucket())
                    .delete(Delete.builder().objects(objects).build())
                    .build()
            )
        ).then();
    }

    private String buildS3Key(String mediaId, String mimeType,
                               MediaOwnerType ownerType, boolean isThumbnail) {
        String folder = resolveFolder(mimeType, ownerType);
        String ext    = resolveExtension(mimeType);
        if (isThumbnail) {
            return folder + "/thumbs/" + mediaId + "_thumb.jpg";
        }
        return folder + "/" + mediaId + "." + ext;
    }

    private String resolveFolder(String mimeType, MediaOwnerType ownerType) {
        return switch (ownerType) {
            case AVATAR           -> "images/avatars";
            case POST_IMAGE       -> "images/posts";
            case GROUP_BACKGROUND -> "images/groups";
            case COMMENT_IMAGE    -> "images/comments";
            case VIDEO            -> "videos/posts";
            case AUDIO            -> "audio/messages";
            case ATTACHMENT       -> "attachments/messages";
        };
    }

    private String resolveExtension(String mimeType) {
        return switch (mimeType) {
            case "image/webp"       -> "webp";
            case "image/gif"        -> "gif";
            case "video/mp4"        -> "mp4";
            case "audio/aac"        -> "aac";
            case "audio/mpeg"       -> "mp3";
            case "application/pdf"  -> "pdf";
            default                 -> "bin";
        };
    }
}
```

### S3AsyncClient Bean (no explicit credentials — IRSA)

```java
@Configuration
public class S3ClientConfig {

    @Bean
    public S3AsyncClient s3AsyncClient(S3Properties props) {
        return S3AsyncClient.crtBuilder()
            // IRSA credentials được inject tự động qua DefaultCredentialsProvider
            // khi Pod chạy trên EKS với ServiceAccount annotation đúng
            .credentialsProvider(DefaultCredentialsProvider.create())
            .region(Region.of(props.getRegion()))
            // CRT-based client: multipart upload tự động, concurrent part uploads
            .targetThroughputInGbps(10.0)
            .minimumPartSizeInBytes(8L * 1024 * 1024)   // 8 MB threshold
            .build();
    }

    @Bean
    public S3TransferManager transferManager(S3AsyncClient s3AsyncClient) {
        return S3TransferManager.builder()
            .s3Client(s3AsyncClient)
            .build();
    }
}
```

---

## application.yaml

```yaml
spring:
  r2dbc:
    url: r2dbc:postgresql://${DB_HOST:localhost}:5432/${DB_NAME}
    username: ${DB_USER}
    password: ${DB_PASSWORD}

sssm:
  s3:
    bucket: ${S3_BUCKET:sssm-media-prod}
    region: ${AWS_REGION:us-east-1}
    cdn-base-url: ${CDN_BASE_URL:https://cdn.sssm.io}
  media:
    file-limits:
      avatar: 2097152 # 2 MB
      post-image: 8388608 # 8 MB
      group-background: 5242880 # 5 MB
      video: 536870912 # 512 MB
      audio: 52428800 # 50 MB
      attachment: 52428800 # 50 MB
    transformations:
      avatar:
        width: 400
        height: 400
        format: webp
        quality: 90
      post-image:
        max-width: 2048
        format: webp
        quality: 85
      group-background:
        width: 1500
        height: 500
        format: webp
        quality: 85
      thumbnail:
        width: 300
        height: 300
        format: webp
        quality: 60
      video:
        max-width: 1920
        max-height: 1080
        video-codec: h264
        audio-codec: aac

grpc:
  server:
    port: 9090
```

> **Không có** `CLOUDINARY_*`, `API_KEY`, `API_SECRET` trong config. Credentials duy nhất là IRSA token tự động inject bởi EKS.

---

## Async upload pipeline

```
POST /api/v1/media/upload  (multipart/form-data)
  │
  ├─ 1. TikaFileTypeDetector.detectFromFlux()
  │       → đọc magic bytes từ DataBuffer đầu tiên
  │       → reject nếu không trong allowlist → 415
  │
  ├─ 2. Validate file size theo owner type
  │
  ├─ 3. mediaAssetRepository.save()  status=PROCESSING
  │       → Return 202 Accepted: { mediaId, status: "PROCESSING" }
  │
  └─ 4. Async pipeline (Schedulers.boundedElastic()):

       image → libvips: resize + convert → WebP bytes
               → (nếu POST_IMAGE/VIDEO) tạo thêm JPEG thumbnail
       video → FFmpeg: transcode H.264/AAC MP4
               → FFmpeg extract frame đầu → JPEG thumbnail
       audio → AAC passthrough
       PDF   → passthrough
             │
             ├─ 5. MediaGuardClient.scan() via WebClient
             │       → NSFW / deepfake / malware check
             │       → REJECTED? → updateRejected + Kafka: media.upload.failed
             │
             ├─ 6. S3MediaStorageService.upload() — main file
             │       → PutObject với Content-Type + Cache-Control: immutable
             │       → multipart tự động nếu > 8 MB (CRT client)
             │
             ├─ 6b. S3MediaStorageService.uploadThumbnail() — nếu có thumbnail
             │
             └─ 7. mediaAssetRepository.updateReady(mediaId, s3Key, cdnUrl, ...)
                     → status = READY
                     → Kafka: media.upload.completed
```

### MediaUploadService

```java
@Service
@RequiredArgsConstructor
public class MediaUploadService {

    private final MediaAssetRepository   mediaAssetRepository;
    private final TikaFileTypeDetector   tikaDetector;
    private final S3MediaStorageService  s3StorageService;
    private final MediaGuardClient       guardClient;
    private final MediaTransformer       mediaTransformer;
    private final KafkaMediaEventPublisher kafkaPublisher;
    private final FileLimitProperties    fileLimits;

    public Mono<UploadResponse> initiateUpload(FilePart filePart, MediaOwnerType ownerType,
                                               String ownerId) {
        String mediaId  = UlidGenerator.generate();
        String filename = sanitize(filePart.filename());

        return tikaDetector.detectFromFlux(filename, filePart.content())
            .flatMap(mimeType -> {
                validateSize(ownerType, mimeType, filePart.headers().getContentLength());
                MediaAssetEntity entity = MediaAssetEntity.builder()
                    .id(mediaId).ownerId(ownerId).ownerType(ownerType)
                    .originalFilename(filename).contentType(mimeType)
                    .fileSizeBytes(filePart.headers().getContentLength())
                    .status(MediaStatus.PROCESSING)
                    .build();
                return mediaAssetRepository.save(entity);
            })
            .doOnSuccess(saved ->
                processAsync(saved, filePart, ownerType)
                    .subscribeOn(Schedulers.boundedElastic())
                    .subscribe(v -> {}, ex -> handleProcessingError(saved.getId(), ex))
            )
            .thenReturn(new UploadResponse(mediaId, "PROCESSING"));
    }

    private Mono<Void> processAsync(MediaAssetEntity entity, FilePart filePart,
                                    MediaOwnerType ownerType) {
        return DataBufferUtils.join(filePart.content())
            .flatMap(buffer -> {
                byte[] raw = toByteArray(buffer);
                DataBufferUtils.release(buffer);
                return Mono.just(raw);
            })
            // Transform
            .flatMap(raw -> mediaTransformer.transform(raw, entity.getContentType(), ownerType))
            // Guard scan
            .flatMap(processed ->
                guardClient.scan(entity.getId(), processed.bytes(), processed.mimeType())
                    .flatMap(result -> result.safe()
                        ? Mono.just(processed)
                        : Mono.error(new MediaRejectedByGuardException(result.reason())))
            )
            // Upload main file + thumbnail (parallel)
            .flatMap(processed -> {
                Mono<S3UploadResult> mainUpload =
                    s3StorageService.upload(entity.getId(), processed.bytes(),
                        processed.mimeType(), ownerType);

                Mono<S3UploadResult> thumbUpload = processed.thumbnail() != null
                    ? s3StorageService.uploadThumbnail(entity.getId(),
                        processed.thumbnail(), ownerType)
                    : Mono.empty();

                return Mono.zip(mainUpload, thumbUpload.defaultIfEmpty(S3UploadResult.empty()))
                    .map(tuple -> new S3UploadPair(tuple.getT1(), tuple.getT2()));
            })
            // Persist + notify
            .flatMap(pair ->
                mediaAssetRepository.updateReady(entity.getId(), pair)
                    .doOnSuccess(__ -> kafkaPublisher.publishCompleted(entity.getId(), pair))
            )
            .then();
    }

    private void handleProcessingError(String mediaId, Throwable ex) {
        String reason = ex instanceof MediaRejectedByGuardException g ? g.reason() : ex.getMessage();
        mediaAssetRepository.updateRejected(mediaId, reason)
            .doOnSuccess(__ -> kafkaPublisher.publishFailed(mediaId, reason))
            .subscribe();
    }
}
```

---

## gRPC server — MediaService

Proto: `media/media_service.proto` (không thay đổi interface, chỉ thay nội dung response).

```java
@GrpcService
@RequiredArgsConstructor
public class MediaGrpcService extends ReactorMediaServiceGrpc.MediaServiceImplBase {

    private final MediaAssetRepository repository;

    @Override
    public Mono<MediaResponse> findMediaById(Mono<FindMediaByIdRequest> request) {
        return request.flatMap(req ->
            repository.findById(req.getMediaId())
                .switchIfEmpty(Mono.error(
                    Status.NOT_FOUND.withDescription("Media: " + req.getMediaId())
                        .asRuntimeException()))
                .map(this::toProto)
        );
    }

    @Override
    public Mono<MediaStatusResponse> getMediaStatus(Mono<GetMediaStatusRequest> request) {
        return request.flatMap(req ->
            repository.findById(req.getMediaId())
                .map(m -> MediaStatusResponse.newBuilder()
                    .setMediaId(m.getId())
                    .setStatus(m.getStatus().name())
                    .build())
                .switchIfEmpty(Mono.error(
                    Status.NOT_FOUND.withDescription("Media: " + req.getMediaId())
                        .asRuntimeException()))
        );
    }

    private MediaResponse toProto(MediaAssetEntity m) {
        return MediaResponse.newBuilder()
            .setMediaId(m.getId())
            .setOwnerId(m.getOwnerId())
            .setContentType(m.getContentType() != null ? m.getContentType() : "")
            // cdnUrl = CloudFront URL — dùng thống nhất cho tất cả consumer
            .setCdnUrl(m.getCdnUrl() != null ? m.getCdnUrl() : "")
            .setThumbnailUrl(m.getThumbnailUrl() != null ? m.getThumbnailUrl() : "")
            .setWidth(m.getWidth() != null ? m.getWidth() : 0)
            .setHeight(m.getHeight() != null ? m.getHeight() : 0)
            .setDurationSecs(m.getDurationSeconds() != null ? m.getDurationSeconds() : 0)
            .setStatus(m.getStatus().name())
            .build();
    }
}
```

> **Lưu ý:** `cloudinary_url` đã bị xoá khỏi proto response. Consumer chỉ dùng `cdn_url` (CloudFront) và `thumbnail_url`.  
> Proto field `cloudinary_url` (field number 4 cũ) phải được deprecate với `[deprecated = true]` hoặc xoá và renumber — cần bumping proto version.

### Updated media_service.proto

```protobuf
syntax = "proto3";
package sssm.media;
option java_package         = "io.github.lvoxx.proto.media";
option java_multiple_files  = true;

service MediaService {
  rpc FindMediaById  (FindMediaByIdRequest)  returns (MediaResponse);
  rpc GetMediaStatus (GetMediaStatusRequest) returns (MediaStatusResponse);
}

message FindMediaByIdRequest  { string media_id = 1; }
message GetMediaStatusRequest { string media_id = 1; }

message MediaResponse {
  string media_id       = 1;
  string owner_id       = 2;
  string content_type   = 3;
  // field 4 (cloudinary_url) REMOVED — không còn dùng Cloudinary
  string cdn_url        = 5;   // CloudFront URL
  string thumbnail_url  = 6;
  int32  width          = 7;
  int32  height         = 8;
  int32  duration_secs  = 9;
  string status         = 10;  // PROCESSING | READY | REJECTED | DELETED
}

message MediaStatusResponse {
  string media_id = 1;
  string status   = 2;
}
```

---

## File limits

| Type             | Max size | Tika allowlist    | Output format                  |
| ---------------- | -------- | ----------------- | ------------------------------ |
| Avatar           | 2 MB     | `image/*`         | WebP 400×400                   |
| Post image       | 8 MB     | `image/*`         | WebP max 2048px                |
| Group background | 5 MB     | `image/*`         | WebP 1500×500                  |
| Video            | 512 MB   | `video/*`         | H.264/AAC MP4 + JPEG thumbnail |
| Audio            | 50 MB    | `audio/*`         | AAC passthrough                |
| Attachment       | 50 MB    | `application/pdf` | Passthrough                    |

---

## Kafka

### Published

| Topic                    | Consumers                                            |
| ------------------------ | ---------------------------------------------------- |
| `media.upload.completed` | post-svc, user-svc, comment-svc, private-message-svc |
| `media.upload.failed`    | post-svc, user-svc                                   |

---

## API

```
POST   /api/v1/media/upload                  # multipart/form-data
GET    /api/v1/media/{mediaId}
GET    /api/v1/media/{mediaId}/status
DELETE /api/v1/media/{mediaId}
POST   /api/v1/internal/media/assign         # service-to-service: bind mediaId to owner
```

---

## Cache keys

| Key                          | TTL   |
| ---------------------------- | ----- |
| `media:asset:{mediaId}`      | 24 h  |
| `media:owner:{ownerId}:list` | 5 min |

---

## Maven dependencies (thay đổi)

```xml
<!-- Xoá: com.cloudinary:cloudinary-http5 -->

<!-- Thêm: AWS SDK v2 S3 -->
<dependency>
    <groupId>software.amazon.awssdk</groupId>
    <artifactId>s3</artifactId>
</dependency>
<dependency>
    <groupId>software.amazon.awssdk</groupId>
    <artifactId>s3-transfer-manager</artifactId>
</dependency>
<!-- CRT-based client — multipart upload tự động -->
<dependency>
    <groupId>software.amazon.awssdk.crt</groupId>
    <artifactId>aws-crt</artifactId>
</dependency>

<!-- Giữ nguyên -->
<dependency>
    <groupId>org.apache.tika</groupId>
    <artifactId>tika-core</artifactId>
    <version>3.0.0</version>
</dependency>
```

---

## IaC changes summary

| Component                                                 | Thay đổi                                                                            |
| --------------------------------------------------------- | ----------------------------------------------------------------------------------- |
| `terraform/modules/s3/`                                   | Thêm module `media` với S3 bucket, OAC, CloudFront distribution, IAM role           |
| `terraform/modules/dns/`                                  | Thêm Route 53 CNAME: `cdn.{domain}` → CloudFront domain                             |
| `terraform/modules/acm/`                                  | Thêm certificate cho `cdn.{domain}` (us-east-1 — CloudFront yêu cầu)                |
| `helm/charts/media-service/templates/serviceaccount.yaml` | Thêm IRSA annotation                                                                |
| `helm/charts/media-service/values.yaml`                   | Thêm `S3_BUCKET`, `CDN_BASE_URL`; xoá `CLOUDINARY_*`                                |
| `infrastructure/k8s/db-init/media-service/sql/`           | Flyway migration V2: rename/drop `cloudinary_*` columns, thêm `s3_key`, `s3_bucket` |

---

## Tests

- **Unit:** `TikaFileTypeDetectorTest`, `S3MediaStorageServiceTest` (mock S3AsyncClient), `MediaTransformerTest`
- **Integration:** PostgreSQL + Kafka + LocalStack S3 (Testcontainers) + WireMock (media-guard)
- **Automation:** upload image → poll READY → verify CloudFront URL format → soft delete → S3 object removed
