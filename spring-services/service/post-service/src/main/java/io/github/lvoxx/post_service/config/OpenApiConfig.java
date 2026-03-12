package io.github.lvoxx.post_service.config;

import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Cấu hình OpenAPI / Swagger UI cho post-service.
 *
 * <p>
 * Tài liệu API có thể truy cập tại:
 * <ul>
 * <li>Swagger UI: {@code /swagger-ui.html}</li>
 * <li>OpenAPI JSON: {@code /v3/api-docs}</li>
 * </ul>
 *
 * <p>
 * Mọi endpoint đều yêu cầu JWT Bearer token trừ các path trong
 * {@code sssm.security.anonymous-paths}.
 */
@Configuration
@SecurityScheme(name = "bearerAuth", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "JWT", description = "JWT token được cấp bởi Keycloak. "
                + "Thêm vào header: Authorization: Bearer {token}")
public class OpenApiConfig {

        @Value("${spring.application.name}")
        private String applicationName;

        /**
         * Tạo OpenAPI bean với metadata đầy đủ cho post-service.
         *
         * @return cấu hình {@link OpenAPI}
         */
        @Bean
        public OpenAPI openAPI() {
                return new OpenAPI()
                                .info(new Info()
                                                .title("SSSM Social — "
                                                                + applicationName.replace("-", " ").toUpperCase())
                                                .description("REST API cho post-service — a part of SSSM Social Platform microservices.")
                                                .version("1.0.0")
                                                .contact(new Contact()
                                                                .name("SSSM Social Platform Team")
                                                                .email("lvoxxartist@gmail.com"))
                                                .license(new License()
                                                                .name("Proprietary")
                                                                .url("https://sssm.io")));
        }
}
