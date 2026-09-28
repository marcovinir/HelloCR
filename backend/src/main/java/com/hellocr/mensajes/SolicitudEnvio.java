package com.hellocr.mensajes;

import java.util.UUID;

/** Cuerpo de /app/mensajes.enviar. El idCliente lo genera el celular: reintentar con el mismo no duplica. */
public record SolicitudEnvio(UUID idCliente, UUID conversacionId, String texto) {
}
