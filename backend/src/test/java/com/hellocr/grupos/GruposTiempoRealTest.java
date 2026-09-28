package com.hellocr.grupos;

import static org.assertj.core.api.Assertions.assertThat;

import com.hellocr.soporte.ClienteStomp;
import com.hellocr.soporte.PruebaTiempoReal;
import com.hellocr.soporte.SesionPrueba;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

class GruposTiempoRealTest extends PruebaTiempoReal {

    @Autowired
    private GrupoService grupos;
    @Autowired
    private MiembrosGrupoService miembros;

    private SesionPrueba ana;
    private SesionPrueba luis;
    private SesionPrueba sofia;

    @BeforeEach
    void sesiones() throws Exception {
        ana = sesionDe("ana");
        luis = sesionDe("luis");
        sofia = sesionDe("sofia");
    }

    @Test
    void crearUnGrupoLesAvisaATodosSusMiembros() throws Exception {
        ClienteStomp deAna = conectar(ana);
        ClienteStomp deLuis = conectar(luis);

        UUID grupo = grupos.crear(id(ana), new SolicitudGrupo("Familia", null, List.of(id(luis)))).id();

        for (ClienteStomp cliente : List.of(deAna, deLuis)) {
            assertThat(cliente.esperar("CONVERSACION_ACTUALIZADA").path("conversacionId").asString())
                    .isEqualTo(grupo.toString());
            assertThat(cliente.esperar("MENSAJE_NUEVO").at("/mensaje/evento/evento").asString())
                    .isEqualTo("GRUPO_CREADO");
        }
    }

    @Test
    void alQuitadoLeLlegaSuSalidaPeroNoLosMensajesSiguientes() throws Exception {
        ClienteStomp deAna = conectar(ana);
        ClienteStomp deLuis = conectar(luis);
        ClienteStomp deSofia = conectar(sofia);
        UUID grupo = grupos.crear(id(ana), new SolicitudGrupo("Familia", null, List.of(id(luis), id(sofia)))).id();
        for (ClienteStomp cliente : List.of(deAna, deLuis, deSofia)) {
            cliente.esperar("MENSAJE_NUEVO");
            cliente.esperar("CONVERSACION_ACTUALIZADA");
        }

        miembros.quitar(id(ana), grupo, id(sofia));

        JsonNode salida = deSofia.esperar("MENSAJE_NUEVO");
        assertThat(salida.at("/mensaje/evento/evento").asString()).isEqualTo("MIEMBRO_QUITADO");
        assertThat(salida.at("/mensaje/evento/afectadoId").asString()).isEqualTo(sofia.usuarioId());
        assertThat(deSofia.esperar("CONVERSACION_ACTUALIZADA").path("conversacionId").asString())
                .isEqualTo(grupo.toString());
        assertThat(deLuis.esperar("MENSAJE_NUEVO").at("/mensaje/evento/evento").asString())
                .isEqualTo("MIEMBRO_QUITADO");

        deAna.enviar("/app/mensajes.enviar",
                Map.of("idCliente", UUID.randomUUID(), "conversacionId", grupo, "texto", "¿Quién viene el domingo?"));
        assertThat(deLuis.esperar("MENSAJE_NUEVO").at("/mensaje/texto").asString())
                .isEqualTo("¿Quién viene el domingo?");
        deSofia.sinEventos("MENSAJE_NUEVO", Duration.ofMillis(500));

        deSofia.enviar("/app/mensajes.enviar",
                Map.of("idCliente", UUID.randomUUID(), "conversacionId", grupo, "texto", "¡Esperen!"));
        assertThat(deSofia.esperar("ERROR").path("codigo").asString()).isEqualTo("NO_ES_MIEMBRO");
    }

    private static UUID id(SesionPrueba sesion) {
        return UUID.fromString(sesion.usuarioId());
    }
}
