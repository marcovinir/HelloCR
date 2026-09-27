package com.hellocr.auth;

import com.hellocr.usuarios.Usuario;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record SolicitudCorreo(
        @NotBlank(message = "El correo es obligatorio.") @Email(message = "El correo no es válido.") String correo) {

    public SolicitudCorreo {
        correo = correo == null ? null : Usuario.normalizarCorreo(correo);
    }
}
