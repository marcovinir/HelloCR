package com.hellocr.tiempoReal;

import com.hellocr.comun.CodigoError;
import com.hellocr.comun.ErrorNegocio;
import com.hellocr.conversaciones.ConsultaMembresia;
import com.hellocr.conversaciones.ErroresConversacion;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Spec 7.5: no se guarda nada; el servidor solo filtra avisos demasiado seguidos. */
@Service
public class EscribiendoService {

    static final Duration INTERVALO_MINIMO = Duration.ofSeconds(2);

    private final Map<String, Instant> ultimosAvisos = new ConcurrentHashMap<>();
    private final ConsultaMembresia membresia;
    private final EnviadorEventos enviador;
    private final Clock clock;

    public EscribiendoService(ConsultaMembresia membresia, EnviadorEventos enviador, Clock clock) {
        this.membresia = membresia;
        this.enviador = enviador;
        this.clock = clock;
    }

    /** Avisa a los otros miembros activos; si el mismo usuario avisó hace menos de 2 s, no hace nada. */
    public void avisar(UUID usuarioId, UUID conversacionId) {
        if (conversacionId == null) {
            throw new ErrorNegocio(CodigoError.VALIDACION, "Falta la conversación.");
        }
        if (!membresia.esMiembroActivo(conversacionId, usuarioId)) {
            throw ErroresConversacion.noEsMiembro();
        }
        if (!pasaElFiltro(usuarioId + "|" + conversacionId)) {
            return;
        }
        EventoEscribiendo evento = new EventoEscribiendo(conversacionId, usuarioId);
        membresia.miembrosActivos(conversacionId).stream()
                .filter(otro -> !otro.equals(usuarioId))
                .forEach(otro -> enviador.enviar(otro, evento));
    }

    private boolean pasaElFiltro(String clave) {
        Instant ahora = clock.instant();
        boolean[] pasa = {false};
        ultimosAvisos.compute(clave, (k, anterior) -> {
            if (anterior != null && ahora.isBefore(anterior.plus(INTERVALO_MINIMO))) {
                return anterior;
            }
            pasa[0] = true;
            return ahora;
        });
        return pasa[0];
    }

    @Scheduled(fixedDelay = 10, timeUnit = TimeUnit.MINUTES)
    public void olvidarViejos() {
        Instant limite = clock.instant().minus(Duration.ofMinutes(1));
        ultimosAvisos.values().removeIf(aviso -> aviso.isBefore(limite));
    }
}
