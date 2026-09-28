package com.hellocr.mensajes;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties("app.mensajes")
@Validated
public record MensajesProperties(@Min(1) int limiteCantidad, @NotNull Duration limiteVentana) {
}
