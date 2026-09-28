package com.hellocr.tiempoReal;

import com.hellocr.auth.SesionesRevocadas;
import java.time.Clock;
import java.util.concurrent.TimeUnit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/** Cierra las conexiones con el token vencido y las de quien restableció la contraseña (spec 7.1 y 5.2). */
@Component
public class CierreSesiones {

    private final RegistroSesiones registro;
    private final SesionesWebSocket sesiones;
    private final Clock clock;

    public CierreSesiones(RegistroSesiones registro, SesionesWebSocket sesiones, Clock clock) {
        this.registro = registro;
        this.sesiones = sesiones;
        this.clock = clock;
    }

    @Scheduled(fixedDelay = 60, timeUnit = TimeUnit.SECONDS)
    public void cerrarVencidas() {
        registro.vencidas(clock.instant()).forEach(sesiones::cerrar);
    }

    /** Después del commit: si la revocación se deshace, las conexiones siguen abiertas. */
    @TransactionalEventListener(fallbackExecution = true)
    public void alRevocar(SesionesRevocadas evento) {
        registro.sesionesDe(evento.usuarioId()).forEach(sesiones::cerrar);
    }
}
