package com.hellocr.tiempoReal;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

/**
 * STOMP sobre WebSocket nativo con el broker simple en memoria (spec 7.1). Los frames de cada sesión se procesan
 * y se publican en orden: dos mensajes enviados seguidos reciben secuencias en el orden en que se escribieron.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final AutenticacionStomp autenticacion;
    private final SesionesWebSocket sesiones;
    private final TiempoRealProperties propiedades;
    private final String urlPublica;
    private TaskScheduler programadorLatidos;

    public WebSocketConfig(AutenticacionStomp autenticacion, SesionesWebSocket sesiones,
            TiempoRealProperties propiedades, @Value("${app.url-publica}") String urlPublica) {
        this.autenticacion = autenticacion;
        this.sesiones = sesiones;
        this.propiedades = propiedades;
        this.urlPublica = urlPublica;
    }

    /** Lazy: el programador lo crea la misma configuración de mensajería que usa esta clase. */
    @Autowired
    void usarProgramador(@Lazy @Qualifier("messageBrokerTaskScheduler") TaskScheduler programador) {
        this.programadorLatidos = programador;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registro) {
        registro.addEndpoint("/ws").setAllowedOrigins(urlPublica);
        registro.setPreserveReceiveOrder(true);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registro) {
        long latido = propiedades.latido().toMillis();
        registro.enableSimpleBroker("/queue")
                .setHeartbeatValue(new long[] {latido, latido})
                .setTaskScheduler(programadorLatidos);
        registro.setApplicationDestinationPrefixes("/app");
        registro.setUserDestinationPrefix("/user");
        registro.setPreservePublishOrder(true);
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registro) {
        registro.interceptors(autenticacion);
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registro) {
        registro.setMessageSizeLimit(64 * 1024);
        registro.addDecoratorFactory(sesiones::decorar);
    }
}
