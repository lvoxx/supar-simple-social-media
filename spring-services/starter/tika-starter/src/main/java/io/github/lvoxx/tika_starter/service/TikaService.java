package io.github.lvoxx.tika_starter.service;

import java.io.IOException;
import java.io.InputStream;

import org.apache.tika.Tika;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class TikaService {
    private final Tika tika;

    public String detect(byte[] fileBytes) {
        return tika.detect(fileBytes);
    }

    public String detect(InputStream inputStream) throws IOException {
        return tika.detect(inputStream);
    }

    public String detect(MultipartFile file) throws IOException {
        try (InputStream is = file.getInputStream()) {
            return tika.detect(is);
        }
    }

    public String detect(FilePart filePart) {
        return DataBufferUtils.join(filePart.content())
                .map(dataBuffer -> {
                    try (InputStream is = dataBuffer.asInputStream()) {
                        return tika.detect(is);
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to detect media type", e);
                    } finally {
                        DataBufferUtils.release(dataBuffer);
                    }
                }).defaultIfEmpty("application/octet-stream")
                .block();
    }
}
