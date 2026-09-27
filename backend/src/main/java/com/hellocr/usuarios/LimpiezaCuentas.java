package com.hellocr.usuarios;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Libera el correo y el @usuario de las cuentas que nadie verificó a tiempo. */
@Component
public class LimpiezaCuentas {

    private static final Logger log = LoggerFactory.getLogger(LimpiezaCuentas.class);

    private final UsuarioRepository usuarios;
    private final CuentasProperties propiedades;
    private final Clock clock;

    public LimpiezaCuentas(UsuarioRepository usuarios, CuentasProperties propiedades, Clock clock) {
        this.usuarios = usuarios;
        this.propiedades = propiedades;
        this.clock = clock;
    }

    /** Todos los días a las 4:10. */
    @Scheduled(cron = "0 10 4 * * *", zone = "${app.zona-horaria}")
    @Transactional
    public int borrarSinVerificar() {
        Instant limite = clock.instant().minus(Duration.ofDays(propiedades.diasSinVerificar()));
        int borradas = usuarios.borrarSinVerificarCreadosAntesDe(limite);
        if (borradas > 0) {
            log.info("Se borraron {} cuentas sin verificar", borradas);
        }
        return borradas;
    }
}
