package com.hellocr.correo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Para desarrollo: el correo (con su enlace) aparece en el log en lugar de enviarse. */
@Component
@ConditionalOnProperty(name = "app.correo.modo", havingValue = "consola", matchIfMissing = true)
public class EnviadorCorreoConsola implements EnviadorCorreo {

    private static final Logger log = LoggerFactory.getLogger(EnviadorCorreoConsola.class);

    @Override
    public void enviar(CorreoSaliente correo) {
        log.info("Correo para {} — {}\n{}", correo.destinatario(), correo.asunto(), correo.texto());
    }
}
