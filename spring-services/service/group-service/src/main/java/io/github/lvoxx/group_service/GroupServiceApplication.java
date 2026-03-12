package io.github.lvoxx.group_service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "io.github.lvoxx")
public class GroupServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(GroupServiceApplication.class, args);
	}

}
