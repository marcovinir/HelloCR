package com.hellocr.tiempoReal;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties("app.tiempo-real")
@Validated
public record TiempoRealProperties(@NotNull Duration latido) {
}
