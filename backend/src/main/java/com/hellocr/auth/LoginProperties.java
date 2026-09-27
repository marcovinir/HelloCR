package com.hellocr.auth;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties("app.login")
@Validated
public record LoginProperties(@Min(1) int maxIntentos, @NotNull Duration bloqueo) {
}
