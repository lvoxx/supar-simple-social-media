package com.lvoxx.sssm.media;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * media-service entrypoint. Unlike post-service there is no scheduled outbox relay: this Phase 1
 * slice only issues presigned R2 uploads, records image metadata, and hands back signed imgproxy
 * URLs — nothing here emits domain events yet (a {@code MediaReady} event can be added in Phase 2
 * once a consumer needs it).
 */
@SpringBootApplication
public class MediaServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(MediaServiceApplication.class, args);
	}

}
