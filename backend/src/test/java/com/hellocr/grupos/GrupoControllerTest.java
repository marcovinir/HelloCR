package com.hellocr.grupos;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hellocr.conversaciones.ChatDirectoService;
import com.hellocr.soporte.PruebaIntegracion;
import com.hellocr.soporte.SesionPrueba;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

class GrupoControllerTest extends PruebaIntegracion {

    @Autowired
    private ChatDirectoService chats;

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
    void crearUnGrupoDevuelveElDetalleYRegistraElEventoInicial() throws Exception {
        String id = idDe(crear(ana, "Familia Rojas", "La familia", luis.usuarioId(), sofia.usuarioId())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tipo").value("GRUPO"))
                .andExpect(jsonPath("$.titulo").value("Familia Rojas"))
                .andExpect(jsonPath("$.descripcion").value("La familia"))
                .andExpect(jsonPath("$.activa").value(true))
                .andExpect(jsonPath("$.miRol").value("ADMIN"))
                .andExpect(jsonPath("$.miembros.length()").value(3)));

        mvc.perform(get("/api/conversaciones/" + id + "/mensajes").with(con(luis)))
                .andExpect(jsonPath("$.mensajes.length()").value(1))
                .andExpect(jsonPath("$.mensajes[0].secuencia").value(1))
                .andExpect(jsonPath("$.mensajes[0].tipo").value("EVENTO"))
                .andExpect(jsonPath("$.mensajes[0].texto").value(nullValue()))
                .andExpect(jsonPath("$.mensajes[0].evento.evento").value("GRUPO_CREADO"))
                .andExpect(jsonPath("$.mensajes[0].remitenteId").value(ana.usuarioId()));
        mvc.perform(get("/api/conversaciones/" + id).with(con(luis)))
                .andExpect(jsonPath("$.miRol").value("MIEMBRO"));
    }

    @Test
    void losDatosInvalidosSon400() throws Exception {
        crear(ana, "  ", null, luis.usuarioId())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores.nombre").value("El grupo necesita un nombre."));
        crear(ana, "Familia", null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores.miembrosIds").value("Elegí al menos una persona."));
        crear(ana, "Familia", null, ana.usuarioId(), luis.usuarioId())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores.miembrosIds").value("No te incluyas en la lista: ya sos parte del grupo."));

        String[] cinco = new String[5];
        for (int i = 0; i < 5; i++) {
            cinco[i] = crearUsuario("invitado" + i).getId().toString();
        }
        crear(ana, "Familia", null, cinco)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores.miembrosIds").value("Un grupo puede tener hasta 5 personas, contándote a vos."));
    }

    @Test
    void unInvitadoInexistenteEs404YLosRepetidosCuentanUnaVez() throws Exception {
        crear(ana, "Familia", null, UUID.randomUUID().toString()).andExpect(status().isNotFound());
        crear(ana, "Familia", null, luis.usuarioId(), luis.usuarioId())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.miembros.length()").value(2));
    }

    @Test
    void cambiarElNombreRegistraUnEventoYLaDescripcionNo() throws Exception {
        String id = idDe(crear(ana, "Familia", null, luis.usuarioId()));

        editar(ana, id, "{\"nombre\": \"  Familia Rojas \"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.titulo").value("Familia Rojas"));
        editar(ana, id, "{\"descripcion\": \"Solo cosas lindas\"}")
                .andExpect(jsonPath("$.descripcion").value("Solo cosas lindas"));
        editar(ana, id, "{\"nombre\": \"Familia Rojas\"}").andExpect(status().isOk());
        editar(ana, id, "{\"descripcion\": \"\"}").andExpect(jsonPath("$.descripcion").value(nullValue()));

        mvc.perform(get("/api/conversaciones/" + id + "/mensajes").with(con(luis)))
                .andExpect(jsonPath("$.mensajes[*].evento.evento").value(contains("GRUPO_CREADO", "NOMBRE_CAMBIADO")))
                .andExpect(jsonPath("$.mensajes[1].evento.valor").value("Familia Rojas"));
    }

    @Test
    void soloLosAdminsEditanYQuienNoEsDelGrupoNiLoVe() throws Exception {
        String id = idDe(crear(ana, "Familia", null, luis.usuarioId()));
        UUID chat = chats.abrir(UUID.fromString(ana.usuarioId()), UUID.fromString(luis.usuarioId())).detalle().id();

        editar(luis, id, "{\"nombre\": \"Otra\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("SIN_PERMISO"))
                .andExpect(jsonPath("$.detail").value("Solo los administradores del grupo pueden hacer esto."));
        editar(sofia, id, "{\"nombre\": \"Otra\"}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("El grupo no existe."));
        editar(ana, chat.toString(), "{\"nombre\": \"Otra\"}").andExpect(status().isNotFound());
        editar(ana, id, "{\"nombre\": \"\"}").andExpect(status().isBadRequest());
    }

    private ResultActions crear(SesionPrueba sesion, String nombre, String descripcion, String... miembros)
            throws Exception {
        String ids = Arrays.stream(miembros).map(id -> "\"" + id + "\"").collect(Collectors.joining(", "));
        return mvc.perform(post("/api/grupos").with(con(sesion)).contentType(MediaType.APPLICATION_JSON).content("""
                {"nombre": "%s", "descripcion": %s, "miembrosIds": [%s]}
                """.formatted(nombre, descripcion == null ? "null" : "\"" + descripcion + "\"", ids)));
    }

    private ResultActions editar(SesionPrueba sesion, String grupo, String json) throws Exception {
        return mvc.perform(patch("/api/grupos/" + grupo).with(con(sesion))
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private static String idDe(ResultActions resultado) throws Exception {
        return JsonPath.read(resultado.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8), "$.id");
    }
}
