package com.hellocr.tiempoReal;

import com.hellocr.comun.UsuarioAutenticado;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

/**
 * Autoriza cada frame que manda el cliente (spec 7.1). Un frame rechazado no sigue: la sesión recibe un frame
 * ERROR con el motivo y Spring cierra la conexión.
 */
@Component
public class AutenticacionStomp implements ChannelInterceptor {

    public static final String COLA_EVENTOS = "/user/queue/eventos";
    static final Set<String> DESTINOS_PERMITIDOS = Set.of("/app/mensajes.enviar", "/app/mensajes.entregados",
            "/app/mensajes.leidos", "/app/escribiendo", "/app/sesion.renovar");
    private static final String PREFIJO_BEARER = "Bearer ";

    private final JwtDecoder decoder;
    private final MessageChannel salida;

    /** Lazy: el canal de salida lo crea la misma configuración de mensajería que registra este interceptor. */
    public AutenticacionStomp(JwtDecoder decoder,
            @Lazy @Qualifier("clientOutboundChannel") MessageChannel salida) {
        this.decoder = decoder;
        this.salida = salida;
    }

    @Override
    public Message<?> preSend(Message<?> mensaje, MessageChannel canal) {
        StompHeaderAccessor acceso = MessageHeaderAccessor.getAccessor(mensaje, StompHeaderAccessor.class);
        if (acceso == null || acceso.getCommand() == null) {
            return mensaje;
        }
        String rechazo = switch (acceso.getCommand()) {
            case CONNECT, STOMP -> autenticar(acceso);
            case SUBSCRIBE -> COLA_EVENTOS.equals(acceso.getDestination()) ? null
                    : "Solo podés suscribirte a " + COLA_EVENTOS + ".";
            case SEND -> acceso.getDestination() != null && DESTINOS_PERMITIDOS.contains(acceso.getDestination())
                    ? null : "Ese destino no existe.";
            default -> null;
        };
        if (rechazo == null) {
            return mensaje;
        }
        rechazar(acceso.getSessionId(), rechazo);
        return null;
    }

    /** Valida "Bearer <jwt>" con el mismo JwtDecoder de la API (vigencia y emisor). */
    public Optional<Jwt> decodificar(String authorization) {
        if (authorization == null || !authorization.startsWith(PREFIJO_BEARER)) {
            return Optional.empty();
        }
        try {
            return Optional.of(decoder.decode(authorization.substring(PREFIJO_BEARER.length())));
        } catch (JwtException invalido) {
            return Optional.empty();
        }
    }

    /** Deja el usuario en la sesión; si el token falta o no sirve, devuelve el motivo del rechazo. */
    private String autenticar(StompHeaderAccessor acceso) {
        Optional<Jwt> jwt = decodificar(acceso.getFirstNativeHeader(HttpHeaders.AUTHORIZATION));
        if (jwt.isEmpty()) {
            return "Falta el token o no es válido.";
        }
        acceso.setUser(new UsuarioStomp(UsuarioAutenticado.id(jwt.get()), jwt.get().getExpiresAt()));
        return null;
    }

    /**
     * Manda el ERROR por el canal de salida: al entregarlo, Spring cierra la conexión. Lanzar una excepción no
     * sirve, porque con setPreserveReceiveOrder(true) Spring solo la anota en el log y la sesión sigue abierta.
     */
    private void rechazar(String sesionId, String motivo) {
        StompHeaderAccessor error = StompHeaderAccessor.create(StompCommand.ERROR);
        error.setMessage(motivo);
        error.setSessionId(sesionId);
        salida.send(MessageBuilder.createMessage(new byte[0], error.getMessageHeaders()));
    }
}
