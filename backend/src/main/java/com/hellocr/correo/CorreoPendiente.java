package com.hellocr.correo;

/** Evento: hay un correo para enviar cuando termine la transacción. */
public record CorreoPendiente(CorreoSaliente correo) {
}
