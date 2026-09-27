package com.hellocr.correo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties("app.correo")
@Validated
public record CorreoProperties(
        @NotNull Modo modo,
        @NotBlank String remitente,
        boolean asincrono,
        @NotNull Duration duracionVerificacion,
        @NotNull Duration duracionRecuperacion,
        @NotNull Duration esperaReenvio) {

    public enum Modo {
        CONSOLA, SMTP
    }
}
