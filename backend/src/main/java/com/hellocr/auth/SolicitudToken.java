package com.hellocr.auth;

import jakarta.validation.constraints.NotBlank;

public record SolicitudToken(@NotBlank(message = "Falta el token del enlace.") String token) {
}
