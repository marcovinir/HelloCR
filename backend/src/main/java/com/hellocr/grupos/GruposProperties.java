package com.hellocr.grupos;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** maxMiembros cuenta a los miembros activos, incluido quien creó el grupo. */
@ConfigurationProperties("app.grupos")
@Validated
public record GruposProperties(@Min(2) int maxMiembros) {
}
