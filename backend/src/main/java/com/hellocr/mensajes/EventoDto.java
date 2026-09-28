package com.hellocr.mensajes;

import java.util.UUID;

/** Datos de un mensaje EVENTO: "Ana agregó a Luis", "Ana cambió el nombre a …". */
public record EventoDto(String evento, UUID afectadoId, String valor) {
}
