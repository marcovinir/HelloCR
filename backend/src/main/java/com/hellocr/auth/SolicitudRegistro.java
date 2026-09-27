package com.hellocr.auth;

import com.hellocr.usuarios.Usuario;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record SolicitudRegistro(
        @NotBlank(message = "El correo es obligatorio.")
        @Email(message = "El correo no es válido.")
        @Size(max = 255, message = "El correo es demasiado largo.")
        String correo,

        @NotBlank(message = "El nombre de usuario es obligatorio.")
        @Pattern(regexp = Usuario.FORMATO_NOMBRE_USUARIO, message = Usuario.MENSAJE_FORMATO_NOMBRE_USUARIO)
        String nombreUsuario,

        @NotBlank(message = "El nombre es obligatorio.")
        @Size(max = 50, message = "El nombre puede tener hasta 50 caracteres.")
        String nombreVisible,

        @NotBlank(message = "La contraseña es obligatoria.")
        @Size(min = 8, max = 64, message = "La contraseña debe tener entre 8 y 64 caracteres.")
        String contrasena) {

    /** Se normaliza antes de validar: el autocompletado del celular agrega espacios y mayúsculas. */
    public SolicitudRegistro {
        correo = correo == null ? null : Usuario.normalizarCorreo(correo);
        nombreUsuario = nombreUsuario == null ? null : Usuario.normalizarNombreUsuario(nombreUsuario);
        nombreVisible = nombreVisible == null ? null : nombreVisible.trim();
    }
}
