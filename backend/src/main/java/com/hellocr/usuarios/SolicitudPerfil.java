package com.hellocr.usuarios;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Todos los campos son opcionales: null significa "no cambiar". Una info vacía la borra. */
public record SolicitudPerfil(
        @Size(min = 1, max = 50, message = "El nombre debe tener entre 1 y 50 caracteres.")
        String nombreVisible,

        @Size(max = 140, message = "La info puede tener hasta 140 caracteres.")
        String info,

        @Pattern(regexp = Usuario.FORMATO_NOMBRE_USUARIO, message = Usuario.MENSAJE_FORMATO_NOMBRE_USUARIO)
        String nombreUsuario) {

    public SolicitudPerfil {
        nombreVisible = nombreVisible == null ? null : nombreVisible.trim();
        info = info == null ? null : info.trim();
        nombreUsuario = nombreUsuario == null ? null : Usuario.normalizarNombreUsuario(nombreUsuario);
    }
}
