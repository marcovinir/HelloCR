package com.hellocr.auth;

import com.hellocr.comun.ErrorNegocio;
import java.nio.charset.StandardCharsets;

final class Contrasenas {

    /** BCrypt solo admite 72 bytes; con tildes o eñes eso puede ser menos de 64 caracteres. */
    static final int MAX_BYTES = 72;

    private Contrasenas() {
    }

    static boolean excedeLimite(String contrasena) {
        return contrasena.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES;
    }

    /** 400 con el error en el campo, igual que las demás validaciones del formulario. */
    static void validarLongitud(String contrasena, String campo) {
        if (excedeLimite(contrasena)) {
            throw ErrorNegocio.validacion(campo, "La contraseña es demasiado larga.");
        }
    }
}
