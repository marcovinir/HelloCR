package com.hellocr.tiempoReal;

import java.security.Principal;
import java.time.Instant;
import java.util.UUID;

/** Principal de una sesión STOMP. Su nombre es el id del usuario: así Spring resuelve /user/queue/eventos. */
public record UsuarioStomp(UUID id, Instant venceEn) implements Principal {

    @Override
    public String getName() {
        return id.toString();
    }
}
