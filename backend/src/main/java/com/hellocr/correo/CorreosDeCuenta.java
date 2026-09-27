package com.hellocr.correo;

import com.hellocr.usuarios.Usuario;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/** Arma los correos de la cuenta y los deja pendientes hasta que la transacción se confirme. */
@Component
public class CorreosDeCuenta {

    private final PlantillasCorreo plantillas;
    private final ApplicationEventPublisher eventos;

    public CorreosDeCuenta(PlantillasCorreo plantillas, ApplicationEventPublisher eventos) {
        this.plantillas = plantillas;
        this.eventos = eventos;
    }

    public void enviarVerificacion(Usuario usuario, String token) {
        eventos.publishEvent(new CorreoPendiente(plantillas.verificacion(usuario, token)));
    }

    public void enviarRecuperacion(Usuario usuario, String token) {
        eventos.publishEvent(new CorreoPendiente(plantillas.recuperacion(usuario, token)));
    }
}
