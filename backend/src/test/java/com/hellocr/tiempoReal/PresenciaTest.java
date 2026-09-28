package com.hellocr.tiempoReal;

import static org.assertj.core.api.Assertions.assertThat;

import com.hellocr.conversaciones.ChatDirectoService;
import com.hellocr.soporte.ClienteStomp;
import com.hellocr.soporte.PruebaTiempoReal;
import com.hellocr.soporte.RelojAjustable;
import com.hellocr.soporte.SesionPrueba;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

class PresenciaTest extends PruebaTiempoReal {

    @Autowired
    private ChatDirectoService chats;

    private SesionPrueba ana;
    private SesionPrueba luis;
    private SesionPrueba sofia;

    @BeforeEach
    void anaYLuisTienenUnChat() throws Exception {
        ana = sesionDe("ana");
        luis = sesionDe("luis");
        sofia = sesionDe("sofia");
        chats.abrir(UUID.fromString(ana.usuarioId()), UUID.fromString(luis.usuarioId()));
    }

    @Test
    void alConectarseLoAvisaSoloASusContactosDeChatsDirectos() throws Exception {
        ClienteStomp deLuis = conectar(luis);
        ClienteStomp deSofia = conectar(sofia);

        conectar(ana);

        JsonNode presencia = deLuis.esperar("PRESENCIA");
        assertThat(presencia.path("usuarioId").asString()).isEqualTo(ana.usuarioId());
        assertThat(presencia.path("enLinea").asBoolean()).isTrue();
        deSofia.sinEventos("PRESENCIA", Duration.ofMillis(300));
    }

    @Test
    void conDosDispositivosSigueEnLineaHastaCerrarElUltimo() throws Exception {
        ClienteStomp deLuis = conectar(luis);
        ClienteStomp celular = conectar(ana);
        deLuis.esperar("PRESENCIA");
        ClienteStomp pc = conectar(ana);

        celular.cerrar();
        deLuis.sinEventos("PRESENCIA", Duration.ofMillis(400));
        pc.cerrar();

        JsonNode desconexion = deLuis.esperar("PRESENCIA");
        assertThat(desconexion.path("enLinea").asBoolean()).isFalse();
        assertThat(desconexion.path("ultimaConexion").asString()).isEqualTo("2026-10-01T15:00:00Z");
        assertThat(jdbc.queryForObject("SELECT ultima_conexion FROM usuarios WHERE nombre_usuario = 'ana'",
                OffsetDateTime.class).toInstant()).isEqualTo(RelojAjustable.INICIO);
    }
}
