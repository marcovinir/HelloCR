package com.hellocr.soporte;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/** Reloj de pruebas: arranca siempre en INICIO y se mueve solo cuando el test lo pide. */
public class RelojAjustable extends Clock {

    /** Jueves 1 de octubre de 2026, 09:00 en Costa Rica (UTC-6). */
    public static final Instant INICIO = Instant.parse("2026-10-01T15:00:00Z");

    private final ZoneId zona;
    private volatile Instant ahora = INICIO;

    public RelojAjustable(ZoneId zona) {
        this.zona = zona;
    }

    public void reiniciar() {
        ahora = INICIO;
    }

    public void fijar(Instant instante) {
        ahora = instante;
    }

    public void avanzar(Duration duracion) {
        ahora = ahora.plus(duracion);
    }

    @Override
    public ZoneId getZone() {
        return zona;
    }

    @Override
    public Clock withZone(ZoneId otraZona) {
        return Clock.fixed(ahora, otraZona);
    }

    @Override
    public Instant instant() {
        return ahora;
    }
}
