package com.hellocr.auth;

import jakarta.validation.constraints.NotBlank;

public record SolicitudLogin(
        @NotBlank(message = "Escribí tu correo o tu nombre de usuario.") String identificador,
        @NotBlank(message = "Escribí tu contraseña.") String contrasena) {
}
