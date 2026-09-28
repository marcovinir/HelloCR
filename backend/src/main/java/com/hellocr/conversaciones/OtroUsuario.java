package com.hellocr.conversaciones;

import java.time.Instant;
import java.util.UUID;

/** La otra persona de un chat directo: su perfil público más su presencia (spec 9.2). */
public record OtroUsuario(UUID id, String nombreUsuario, String nombreVisible, String info, boolean enLinea,
        Instant ultimaConexion) {
}
