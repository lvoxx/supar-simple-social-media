package io.github.lvoxx.message_notification_service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "io.github.lvoxx")
public class MessageNotificationServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(MessageNotificationServiceApplication.class, args);
	}

}
