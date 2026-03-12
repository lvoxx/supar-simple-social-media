package io.github.lvoxx.private_message_service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "io.github.lvoxx")
public class PrivateMessageServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(PrivateMessageServiceApplication.class, args);
	}

}
