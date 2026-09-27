package com.hellocr.usuarios;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties("app.cuentas")
@Validated
public record CuentasProperties(@Min(1) int diasSinVerificar) {
}
