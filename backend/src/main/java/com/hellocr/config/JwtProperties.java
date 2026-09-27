package com.hellocr.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties("app.jwt")
@Validated
public record JwtProperties(
        @NotBlank @Size(min = 32, message = "app.jwt.secreto debe tener al menos 32 caracteres") String secreto,
        @NotNull Duration duracionAccess,
        @NotNull Duration duracionRefresh,
        boolean cookieSegura) {
}
