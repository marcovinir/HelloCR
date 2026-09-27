package com.hellocr.auth;

import java.util.UUID;

/** Se revocaron todas las sesiones del usuario. El plan 2 lo escucha para cerrar sus WebSockets. */
public record SesionesRevocadas(UUID usuarioId) {
}
