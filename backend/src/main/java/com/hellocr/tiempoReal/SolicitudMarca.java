package com.hellocr.tiempoReal;

import java.util.UUID;

/** Cuerpo de /app/mensajes.entregados y /app/mensajes.leidos. */
public record SolicitudMarca(UUID conversacionId, Long hastaSecuencia) {
}
