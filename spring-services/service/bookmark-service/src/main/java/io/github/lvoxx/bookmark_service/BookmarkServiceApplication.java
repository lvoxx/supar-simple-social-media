package io.github.lvoxx.bookmark_service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "io.github.lvoxx")
public class BookmarkServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(BookmarkServiceApplication.class, args);
	}

}
