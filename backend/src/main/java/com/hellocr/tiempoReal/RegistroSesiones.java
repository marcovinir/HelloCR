package com.hellocr.tiempoReal;

import com.hellocr.conversaciones.ConsultaPresencia;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Sesiones STOMP abiertas y cuándo vence el token de cada una, en memoria (hay un solo servidor). */
@Component
public class RegistroSesiones implements ConsultaPresencia {

    private record Sesion(UUID usuarioId, Instant venceEn) {
    }

    private final Map<String, Sesion> sesiones = new HashMap<>();

    /** Registra la sesión; devuelve true si es la primera del usuario (pasa a estar en línea). */
    public synchronized boolean abrir(String sesionId, UUID usuarioId, Instant venceEn) {
        boolean primera = !enLinea(usuarioId);
        sesiones.put(sesionId, new Sesion(usuarioId, venceEn));
        return primera;
    }

    /** Quita la sesión; si era la última del usuario, devuelve su id (pasa a estar desconectado). */
    public synchronized Optional<UUID> cerrar(String sesionId) {
        Sesion quitada = sesiones.remove(sesionId);
        if (quitada == null || enLinea(quitada.usuarioId())) {
            return Optional.empty();
        }
        return Optional.of(quitada.usuarioId());
    }

    /** Solo la dueña de la sesión puede extenderla. */
    public synchronized boolean renovar(String sesionId, UUID usuarioId, Instant venceEn) {
        Sesion actual = sesiones.get(sesionId);
        if (actual == null || !actual.usuarioId().equals(usuarioId)) {
            return false;
        }
        sesiones.put(sesionId, new Sesion(usuarioId, venceEn));
        return true;
    }

    public synchronized List<String> vencidas(Instant ahora) {
        return sesiones.entrySet().stream()
                .filter(entrada -> !entrada.getValue().venceEn().isAfter(ahora))
                .map(Map.Entry::getKey).toList();
    }

    public synchronized List<String> sesionesDe(UUID usuarioId) {
        return sesiones.entrySet().stream()
                .filter(entrada -> entrada.getValue().usuarioId().equals(usuarioId))
                .map(Map.Entry::getKey).toList();
    }

    @Override
    public synchronized boolean enLinea(UUID usuarioId) {
        return sesiones.values().stream().anyMatch(sesion -> sesion.usuarioId().equals(usuarioId));
    }

    public synchronized int cantidad() {
        return sesiones.size();
    }
}
