package com.hellocr.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * El identificador tiene tope porque cada intento fallido lo guarda en memoria (LimiteIntentosLogin):
 * sin límite, alguien sin cuenta podría agotar la memoria del servidor con textos enormes.
 */
public record SolicitudLogin(
        @NotBlank(message = "Escribí tu correo o tu nombre de usuario.")
        @Size(max = 255, message = "Ese correo o nombre de usuario es demasiado largo.")
        String identificador,

        @NotBlank(message = "Escribí tu contraseña.") String contrasena) {
}
