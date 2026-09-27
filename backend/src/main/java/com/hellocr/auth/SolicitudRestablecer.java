package com.hellocr.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SolicitudRestablecer(
        @NotBlank(message = "Falta el token del enlace.") String token,
        @NotBlank(message = "La contraseña es obligatoria.")
        @Size(min = 8, max = 64, message = "La contraseña debe tener entre 8 y 64 caracteres.")
        String contrasenaNueva) {
}
