package com.hellocr.mensajes;

import java.time.Instant;
import java.util.UUID;

/** Spec 7.3. En un EVENTO, texto es null y evento trae los datos; en un TEXTO, al revés. El plan 3 agrega archivo. */
public record MensajeDto(UUID conversacionId, long secuencia, UUID idCliente, UUID remitenteId, TipoMensaje tipo,
        String texto, EventoDto evento, Instant creadoEn) {
}
