package com.hellocr.comun;

import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;

/** El sujeto del access token es el id del usuario. */
public final class UsuarioAutenticado {

    private UsuarioAutenticado() {
    }

    public static UUID id(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
