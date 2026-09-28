package com.hellocr.conversaciones;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hellocr.soporte.PruebaIntegracion;
import com.hellocr.soporte.SesionPrueba;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

class ChatDirectoControllerTest extends PruebaIntegracion {

    @Autowired
    private ChatDirectoService chats;

    private SesionPrueba ana;
    private SesionPrueba luis;

    @BeforeEach
    void sesiones() throws Exception {
        ana = sesionDe("ana");
        luis = sesionDe("luis");
    }

    @Test
    void abrirUnChatNuevoResponde201ConElDetalle() throws Exception {
        abrir(ana, luis.usuarioId())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tipo").value("DIRECTA"))
                .andExpect(jsonPath("$.titulo").value("Usuario luis"))
                .andExpect(jsonPath("$.activa").value(true))
                .andExpect(jsonPath("$.miRol").value("MIEMBRO"))
                .andExpect(jsonPath("$.miembros.length()").value(2))
                .andExpect(jsonPath("$.miembros[0].usuario.nombreUsuario").value("ana"))
                .andExpect(jsonPath("$.miembros[0].ultimaEntregada").value(0))
                .andExpect(jsonPath("$.miembros[0].periodos[0].desde").value(1))
                .andExpect(jsonPath("$.miembros[0].periodos[0].hasta").value(nullValue()))
                .andExpect(jsonPath("$.miembros[1].usuario.correo").doesNotExist());
    }

    @Test
    void abrirloDeNuevoDesdeCualquieraDeLosDosDevuelveElMismoChat() throws Exception {
        String id = idDe(abrir(ana, luis.usuarioId()).andExpect(status().isCreated()));

        abrir(ana, luis.usuarioId()).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));
        abrir(luis, ana.usuarioId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.titulo").value("Usuario ana"));
    }

    @Test
    void noSePuedeAbrirUnChatConUnoMismo() throws Exception {
        abrir(ana, ana.usuarioId())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.codigo").value("CHAT_CONSIGO_MISMO"));
    }

    @Test
    void conAlguienInexistenteOSinVerificarEs404() throws Exception {
        UUID sofia = crearUsuarioSinVerificar("sofia").getId();

        abrir(ana, UUID.randomUUID().toString()).andExpect(status().isNotFound());
        abrir(ana, sofia.toString())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value("NO_ENCONTRADO"));
    }

    @Test
    void sinUsuarioOConUnIdInvalidoEs400() throws Exception {
        mvc.perform(post("/api/conversaciones/directas").with(con(ana))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores.usuarioId").value("Falta la persona con quien chatear."));
        abrir(ana, "no-es-un-uuid").andExpect(status().isBadRequest());
    }

    @Test
    void dosAperturasSimultaneasDejanUnSoloChat() throws Exception {
        UUID idAna = UUID.fromString(ana.usuarioId());
        UUID idLuis = UUID.fromString(luis.usuarioId());
        List<Callable<ChatDirectoService.Apertura>> tareas = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            tareas.add(() -> chats.abrir(idAna, idLuis));
            tareas.add(() -> chats.abrir(idLuis, idAna));
        }

        List<ChatDirectoService.Apertura> aperturas = enParalelo(tareas);

        assertThat(aperturas).extracting(apertura -> apertura.detalle().id()).containsOnly(aperturas.getFirst().detalle().id());
        assertThat(aperturas).filteredOn(ChatDirectoService.Apertura::creada).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM conversaciones", Integer.class)).isEqualTo(1);
    }

    @Test
    void elDetalleSoloLoVenLosMiembros() throws Exception {
        String id = idDe(abrir(ana, luis.usuarioId()));
        SesionPrueba sofia = sesionDe("sofia");

        mvc.perform(get("/api/conversaciones/{id}", id).with(con(ana)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.titulo").value("Usuario luis"));
        mvc.perform(get("/api/conversaciones/{id}", id).with(con(sofia)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("La conversación no existe."));
        mvc.perform(get("/api/conversaciones/{id}", UUID.randomUUID()).with(con(ana)))
                .andExpect(status().isNotFound());
    }

    private ResultActions abrir(SesionPrueba sesion, String usuarioId) throws Exception {
        return mvc.perform(post("/api/conversaciones/directas").with(con(sesion))
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"usuarioId": "%s"}
                        """.formatted(usuarioId)));
    }

    private static String idDe(ResultActions resultado) throws Exception {
        return JsonPath.read(resultado.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8), "$.id");
    }
}
