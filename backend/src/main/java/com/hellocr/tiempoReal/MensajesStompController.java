package com.hellocr.tiempoReal;

import com.hellocr.comun.CodigoError;
import com.hellocr.comun.ErrorNegocio;
import com.hellocr.mensajes.EnvioMensajesService;
import com.hellocr.mensajes.MarcasService;
import com.hellocr.mensajes.SolicitudEnvio;
import java.security.Principal;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.converter.MessageConversionException;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Controller;

/** Los destinos /app/… de spec 7.2. Solo traduce frames a llamadas de servicio y errores a eventos ERROR. */
@Controller
public class MensajesStompController {

    private static final Logger log = LoggerFactory.getLogger(MensajesStompController.class);

    private final EnvioMensajesService envio;
    private final MarcasService marcas;
    private final EscribiendoService escribiendo;
    private final RenovacionSesion renovacion;
    private final EnviadorEventos enviador;

    public MensajesStompController(EnvioMensajesService envio, MarcasService marcas, EscribiendoService escribiendo,
            RenovacionSesion renovacion, EnviadorEventos enviador) {
        this.envio = envio;
        this.marcas = marcas;
        this.escribiendo = escribiendo;
        this.renovacion = renovacion;
        this.enviador = enviador;
    }

    /** Un mensaje nuevo lo reparte EntregaTiempoReal; un reintento solo se le reenvía al remitente. */
    @MessageMapping("mensajes.enviar")
    public void enviar(@Payload SolicitudEnvio solicitud, Principal usuario) {
        UUID remitente = id(usuario);
        EnvioMensajesService.Resultado resultado;
        try {
            resultado = envio.enviar(remitente, solicitud);
        } catch (ErrorNegocio error) {
            throw new ErrorDeEnvio(solicitud.idCliente(), error);
        }
        if (!resultado.nuevo()) {
            enviador.enviar(remitente, new EventoMensajeNuevo(resultado.mensaje()));
        }
    }

    @MessageMapping("mensajes.entregados")
    public void entregados(@Payload SolicitudMarca solicitud, Principal usuario) {
        marcas.entregados(id(usuario), solicitud.conversacionId(), solicitud.hastaSecuencia());
    }

    @MessageMapping("mensajes.leidos")
    public void leidos(@Payload SolicitudMarca solicitud, Principal usuario) {
        marcas.leidos(id(usuario), solicitud.conversacionId(), solicitud.hastaSecuencia());
    }

    @MessageMapping("escribiendo")
    public void escribiendo(@Payload SolicitudEscribiendo solicitud, Principal usuario) {
        escribiendo.avisar(id(usuario), solicitud.conversacionId());
    }

    @MessageMapping("sesion.renovar")
    public void renovar(Message<?> mensaje, Principal usuario) {
        StompHeaderAccessor acceso = StompHeaderAccessor.wrap(mensaje);
        renovacion.renovar(acceso.getSessionId(), id(usuario), acceso.getFirstNativeHeader(HttpHeaders.AUTHORIZATION));
    }

    @MessageExceptionHandler
    @SendToUser(destinations = EnviadorEventosStomp.DESTINO, broadcast = false)
    public EventoError alFallarUnEnvio(ErrorDeEnvio error) {
        return EventoError.de(error.idCliente(), error.causa());
    }

    @MessageExceptionHandler
    @SendToUser(destinations = EnviadorEventosStomp.DESTINO, broadcast = false)
    public EventoError alFallar(ErrorNegocio error) {
        return EventoError.de(null, error);
    }

    @MessageExceptionHandler
    @SendToUser(destinations = EnviadorEventosStomp.DESTINO, broadcast = false)
    public EventoError alFallarSinEsperarlo(Exception error) {
        if (error instanceof MessageConversionException) {
            return EventoError.de(CodigoError.VALIDACION, "El mensaje no tiene un formato válido.");
        }
        log.error("Error inesperado en un frame STOMP", error);
        return EventoError.de(CodigoError.ERROR_INTERNO, "Ocurrió un error inesperado. Intentá de nuevo.");
    }

    private static UUID id(Principal usuario) {
        return UUID.fromString(usuario.getName());
    }
}
