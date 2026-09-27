package com.hellocr.auth;

import com.hellocr.usuarios.Usuario;

/** Lo que la persona escribe para entrar: su correo o su @usuario, ya normalizado. */
record Identificador(String valor, boolean esCorreo) {

    static Identificador de(String texto) {
        String limpio = texto.trim();
        return limpio.indexOf('@') > 0
                ? new Identificador(Usuario.normalizarCorreo(limpio), true)
                : new Identificador(Usuario.normalizarNombreUsuario(limpio), false);
    }
}
