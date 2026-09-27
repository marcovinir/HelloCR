package com.hellocr.correo;

/** Un correo listo para enviar, en texto plano y en HTML. */
public record CorreoSaliente(String destinatario, String asunto, String texto, String html) {
}
