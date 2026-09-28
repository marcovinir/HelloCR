package com.hellocr.tiempoReal;

import static org.assertj.core.api.Assertions.assertThat;

import com.hellocr.auth.JwtService;
import com.hellocr.conversaciones.ChatDirectoService;
import com.hellocr.soporte.ClienteStomp;
import com.hellocr.soporte.PruebaTiempoReal;
import com.hellocr.soporte.SesionPrueba;
import com.hellocr.usuarios.UsuarioRepository;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

class MensajesTiempoRealTest extends PruebaTiempoReal {

    private static final Duration UN_RATO = Duration.ofMillis(500);

    @Autowired
    private ChatDirectoService chats;
    @Autowired
    private CierreSesiones cierre;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private UsuarioRepository usuarios;

    private SesionPrueba ana;
    private SesionPrueba luis;
    private UUID chat;

    @BeforeEach
    void chatEntreAnaYLuis() throws Exception {
        ana = sesionDe("ana");
        luis = sesionDe("luis");
        chat = chats.abrir(UUID.fromString(ana.usuarioId()), UUID.fromString(luis.usuarioId())).detalle().id();
    }

    @Test
    void anaEnviaYLeLlegaALuisYATodosLosDispositivosDeAna() throws Exception {
        ClienteStomp celularDeAna = conectar(ana);
        ClienteStomp pcDeAna = conectar(ana);
        ClienteStomp deLuis = conectar(luis);
        UUID idCliente = UUID.randomUUID();

        celularDeAna.enviar("/app/mensajes.enviar", envio(idCliente, "¡Hola, Luis!"));

        JsonNode recibido = deLuis.esperar("MENSAJE_NUEVO");
        assertThat(recibido.at("/mensaje/secuencia").asLong()).isEqualTo(1);
        assertThat(recibido.at("/mensaje/texto").asString()).isEqualTo("¡Hola, Luis!");
        assertThat(recibido.at("/mensaje/tipo").asString()).isEqualTo("TEXTO");
        assertThat(recibido.at("/mensaje/remitenteId").asString()).isEqualTo(ana.usuarioId());
        assertThat(recibido.at("/mensaje/idCliente").asString()).isEqualTo(idCliente.toString());
        assertThat(recibido.at("/mensaje/conversacionId").asString()).isEqualTo(chat.toString());
        assertThat(celularDeAna.esperar("MENSAJE_NUEVO").at("/mensaje/secuencia").asLong()).isEqualTo(1);
        assertThat(pcDeAna.esperar("MENSAJE_NUEVO").at("/mensaje/secuencia").asLong()).isEqualTo(1);
    }

    @Test
    void reenviarElMismoIdClienteSoloLeLlegaDeNuevoAlRemitente() throws Exception {
        ClienteStomp deAna = conectar(ana);
        ClienteStomp deLuis = conectar(luis);
        UUID idCliente = UUID.randomUUID();
        deAna.enviar("/app/mensajes.enviar", envio(idCliente, "original"));
        deAna.esperar("MENSAJE_NUEVO");
        deLuis.esperar("MENSAJE_NUEVO");

        deAna.enviar("/app/mensajes.enviar", envio(idCliente, "reintento"));

        JsonNode repetido = deAna.esperar("MENSAJE_NUEVO");
        assertThat(repetido.at("/mensaje/secuencia").asLong()).isEqualTo(1);
        assertThat(repetido.at("/mensaje/texto").asString()).isEqualTo("original");
        deLuis.sinEventos("MENSAJE_NUEVO", UN_RATO);
    }

    @Test
    void unErrorDeEnvioLlegaSoloALaSesionQueEnvioConSuIdCliente() throws Exception {
        ClienteStomp celularDeAna = conectar(ana);
        ClienteStomp pcDeAna = conectar(ana);
        UUID idCliente = UUID.randomUUID();

        celularDeAna.enviar("/app/mensajes.enviar", envio(idCliente, "   "));

        JsonNode error = celularDeAna.esperar("ERROR");
        assertThat(error.path("idCliente").asString()).isEqualTo(idCliente.toString());
        assertThat(error.path("codigo").asString()).isEqualTo("VALIDACION");
        assertThat(error.path("detalle").asString()).isEqualTo("El mensaje está vacío.");
        pcDeAna.sinEventos("ERROR", UN_RATO);
    }

    @Test
    void quienNoEsMiembroRecibeUnError() throws Exception {
        ClienteStomp deSofia = conectar(sesionDe("sofia"));

        deSofia.enviar("/app/mensajes.enviar", envio(UUID.randomUUID(), "hola"));

        assertThat(deSofia.esperar("ERROR").path("codigo").asString()).isEqualTo("NO_ES_MIEMBRO");
    }

    @Test
    void unCuerpoQueNoEsJsonEsUnErrorDeValidacion() throws Exception {
        ClienteStomp deAna = conectar(ana);

        deAna.enviarBytes("/app/mensajes.enviar", "esto no es json".getBytes(StandardCharsets.UTF_8));

        JsonNode error = deAna.esperar("ERROR");
        assertThat(error.path("codigo").asString()).isEqualTo("VALIDACION");
        assertThat(error.path("idCliente").isNull()).isTrue();
    }

    @Test
    void losMensajesEnviadosSeguidosConservanSuOrden() throws Exception {
        ClienteStomp deAna = conectar(ana);
        ClienteStomp deLuis = conectar(luis);

        for (int i = 1; i <= 10; i++) {
            deAna.enviar("/app/mensajes.enviar", envio(UUID.randomUUID(), String.valueOf(i)));
        }

        for (int i = 1; i <= 10; i++) {
            JsonNode recibido = deLuis.esperar("MENSAJE_NUEVO");
            assertThat(recibido.at("/mensaje/texto").asString()).isEqualTo(String.valueOf(i));
            assertThat(recibido.at("/mensaje/secuencia").asLong()).isEqualTo(i);
        }
    }

    @Test
    void entregadosYLeidosAvisanALosMiembrosYNuncaBajan() throws Exception {
        ClienteStomp deAna = conectar(ana);
        ClienteStomp deLuis = conectar(luis);
        deAna.enviar("/app/mensajes.enviar", envio(UUID.randomUUID(), "hola"));
        deLuis.esperar("MENSAJE_NUEVO");

        deLuis.enviar("/app/mensajes.entregados", Map.of("conversacionId", chat, "hastaSecuencia", 1));

        JsonNode entregado = deAna.esperar("ESTADO_ACTUALIZADO");
        assertThat(entregado.path("conversacionId").asString()).isEqualTo(chat.toString());
        assertThat(entregado.path("usuarioId").asString()).isEqualTo(luis.usuarioId());
        assertThat(entregado.path("ultimaEntregada").asLong()).isEqualTo(1);
        assertThat(entregado.path("ultimaLeida").asLong()).isZero();
        assertThat(deLuis.esperar("ESTADO_ACTUALIZADO").path("ultimaEntregada").asLong()).isEqualTo(1);

        deLuis.enviar("/app/mensajes.leidos", Map.of("conversacionId", chat, "hastaSecuencia", 1));
        assertThat(deAna.esperar("ESTADO_ACTUALIZADO").path("ultimaLeida").asLong()).isEqualTo(1);

        deLuis.enviar("/app/mensajes.leidos", Map.of("conversacionId", chat, "hastaSecuencia", 0));
        deAna.sinEventos("ESTADO_ACTUALIZADO", UN_RATO);
    }

    @Test
    void escribiendoLlegaAlOtroMiembroYRespetaLosDosSegundos() throws Exception {
        ClienteStomp deAna = conectar(ana);
        ClienteStomp deLuis = conectar(luis);

        deAna.enviar("/app/escribiendo", Map.of("conversacionId", chat));
        deAna.enviar("/app/escribiendo", Map.of("conversacionId", chat));

        JsonNode escribiendo = deLuis.esperar("ESCRIBIENDO");
        assertThat(escribiendo.path("usuarioId").asString()).isEqualTo(ana.usuarioId());
        assertThat(escribiendo.path("conversacionId").asString()).isEqualTo(chat.toString());
        deLuis.sinEventos("ESCRIBIENDO", UN_RATO);
        deAna.sinEventos("ESCRIBIENDO", Duration.ZERO);

        reloj.avanzar(Duration.ofSeconds(2));
        deAna.enviar("/app/escribiendo", Map.of("conversacionId", chat));
        deLuis.esperar("ESCRIBIENDO");
    }

    @Test
    void renovarLaSesionEvitaQueSeCierreAlVencerElToken() throws Exception {
        ClienteStomp renovado = conectar(ana);
        ClienteStomp sinRenovar = conectar(ana);
        ClienteStomp deLuis = conectar(luis);
        reloj.avanzar(Duration.ofMinutes(10));
        String tokenNuevo = jwtService.emitir(usuarios.findByNombreUsuario("ana").orElseThrow());

        renovado.renovar(tokenNuevo);
        // Los frames de una sesión se procesan en orden: cuando Luis ve el aviso, la renovación ya se aplicó.
        renovado.enviar("/app/escribiendo", Map.of("conversacionId", chat));
        deLuis.esperar("ESCRIBIENDO");
        reloj.avanzar(Duration.ofMinutes(10));
        cierre.cerrarVencidas();

        assertThat(sinRenovar.esperarCierre()).isTrue();
        assertThat(renovado.estaConectado()).isTrue();
    }

    @Test
    void renovarConUnTokenAjenoOInvalidoDaError() throws Exception {
        ClienteStomp deAna = conectar(ana);

        deAna.renovar(luis.accessToken());
        assertThat(deAna.esperar("ERROR").path("codigo").asString()).isEqualTo("NO_AUTENTICADO");

        deAna.renovar("no-es-un-token");
        assertThat(deAna.esperar("ERROR").path("codigo").asString()).isEqualTo("NO_AUTENTICADO");
    }

    private Map<String, Object> envio(UUID idCliente, String texto) {
        return Map.of("idCliente", idCliente, "conversacionId", chat, "texto", texto);
    }
}
