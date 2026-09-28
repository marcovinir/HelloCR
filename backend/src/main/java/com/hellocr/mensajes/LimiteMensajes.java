package com.hellocr.mensajes;

import com.hellocr.comun.CodigoError;
import com.hellocr.comun.ErrorNegocio;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Ventana deslizante en memoria (hay un solo servidor): app.mensajes.limite-cantidad en limite-ventana. */
@Component
public class LimiteMensajes {

    private final Map<UUID, Deque<Instant>> envios = new ConcurrentHashMap<>();
    private final MensajesProperties propiedades;
    private final Clock clock;

    public LimiteMensajes(MensajesProperties propiedades, Clock clock) {
        this.propiedades = propiedades;
        this.clock = clock;
    }

    /** Cuenta un envío o lanza DEMASIADOS_MENSAJES si el usuario ya llegó al límite. */
    public void consumir(UUID usuarioId) {
        Instant ahora = clock.instant();
        Instant desde = ahora.minus(propiedades.limiteVentana());
        boolean[] permitido = {false};
        envios.compute(usuarioId, (clave, anteriores) -> {
            Deque<Instant> recientes = anteriores == null ? new ArrayDeque<>() : anteriores;
            while (!recientes.isEmpty() && !recientes.peekFirst().isAfter(desde)) {
                recientes.pollFirst();
            }
            if (recientes.size() < propiedades.limiteCantidad()) {
                recientes.addLast(ahora);
                permitido[0] = true;
            }
            return recientes;
        });
        if (!permitido[0]) {
            throw new ErrorNegocio(CodigoError.DEMASIADOS_MENSAJES,
                    "Estás enviando mensajes muy rápido. Esperá unos segundos.");
        }
    }

    @Scheduled(fixedDelay = 10, timeUnit = TimeUnit.MINUTES)
    public void olvidarInactivos() {
        Instant desde = clock.instant().minus(propiedades.limiteVentana());
        envios.entrySet().removeIf(entrada -> entrada.getValue().isEmpty()
                || !entrada.getValue().peekLast().isAfter(desde));
    }
}
