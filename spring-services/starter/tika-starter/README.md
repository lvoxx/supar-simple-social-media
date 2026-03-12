# tika-starter

`Tika Starter` là một **Spring Boot starter** giúp tích hợp nhanh khả năng **detect MIME type / media type từ file content** sử dụng Apache Tika.

Starter này được thiết kế cho các hệ thống:

- File Upload API
- Media Service
- Document Processing
- Content Moderation
- Storage Service (S3 / MinIO / Local)

Nó giúp **xác định loại file dựa trên magic bytes thay vì extension**, tránh các trường hợp fake file như:

```
fake.jpg -> thực chất là file PDF
malware.png -> thực chất là executable
```

---

# Features

- Detect MIME type từ **file content**
- Hỗ trợ `MultipartFile`, `InputStream`, `byte[]`
- Auto configuration cho Spring Boot
- Singleton `Tika` instance (thread-safe usage)
- Optional whitelist validation
- Lightweight dependency

---

# Installation

## Maven

```xml
<dependency>
    <groupId>com.example</groupId>
    <artifactId>tika-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

---

# Auto Configuration

Starter sẽ tự động tạo bean:

```
MediaTypeDetector
```

Bạn có thể inject trực tiếp vào service hoặc controller.

---

# Usage

## Detect từ MultipartFile

```java
@RestController
@RequiredArgsConstructor
public class UploadController {

    private final MediaTypeDetector mediaTypeDetector;

    @PostMapping("/upload")
    public String upload(@RequestParam MultipartFile file) throws IOException {

        String mimeType = mediaTypeDetector.detect(file);

        return mimeType;
    }
}
```

---

## Detect từ InputStream

```java
String mimeType = mediaTypeDetector.detect(inputStream);
```

---

## Detect từ byte[]

```java
String mimeType = mediaTypeDetector.detect(fileBytes);
```

---

# Example Output

```
image/png
image/jpeg
application/pdf
video/mp4
text/plain
application/zip
```

---

# Validate Allowed Media Types

Bạn có thể validate whitelist để chặn file không hợp lệ.

```java
Set<String> allowed = Set.of(
    "image/png",
    "image/jpeg",
    "application/pdf"
);

if (!allowed.contains(mimeType)) {
    throw new IllegalArgumentException("Unsupported file type: " + mimeType);
}
```

---

# Best Practices

Khi xây dựng hệ thống upload production nên kết hợp:

1. **File size validation**

```
max-size: 10MB
```

2. **Extension whitelist**

```
jpg, png, pdf
```

3. **Content detection (Tika)**

```
image/png
application/pdf
```

4. **Optional virus scanning**

```
ClamAV
```

5. **File renaming**

```
UUID filename
```

---

# Architecture

```
tika-starter
 ├── autoconfigure
 │    └── TikaAutoConfiguration
 │
 ├── core
 │    └── MediaTypeDetector
 │
 ├── properties
 │    └── TikaProperties
 │
 └── starter
      └── META-INF/spring
```

---

# Example Implementation

## MediaTypeDetector

```java
@Service
public class MediaTypeDetector {

    private final Tika tika = new Tika();

    public String detect(byte[] bytes) {
        return tika.detect(bytes);
    }

    public String detect(InputStream inputStream) throws IOException {
        return tika.detect(inputStream);
    }

    public String detect(MultipartFile file) throws IOException {
        try (InputStream is = file.getInputStream()) {
            return tika.detect(is);
        }
    }
}
```

---

# Performance Notes

- `Tika` instance nên được **reuse (singleton)**
- Tránh tạo `new Tika()` cho mỗi request
- Detect chỉ đọc **header bytes** nên rất nhanh

Thông thường:

```
~1–3 ms / file
```

---

# Use Cases

### File Upload Service

```
User -> API -> Detect MIME -> Validate -> Store
```

### Media Moderation

```
Upload -> Detect -> AI Scan -> Store
```

### Document Processing

```
Upload -> Detect -> Parse Metadata -> Index
```

---

# License

Apache 2.0 License
