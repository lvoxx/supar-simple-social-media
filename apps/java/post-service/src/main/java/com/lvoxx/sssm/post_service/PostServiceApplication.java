package com.lvoxx.sssm.post_service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * post-service entrypoint. {@link EnableScheduling} powers the transactional-outbox relay, whose
 * {@code @Scheduled} poller drains committed events to Kafka.
 */
@SpringBootApplication
@EnableScheduling
public class PostServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(PostServiceApplication.class, args);
	}

}
