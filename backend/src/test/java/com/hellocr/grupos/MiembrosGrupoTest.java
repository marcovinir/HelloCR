package com.hellocr.grupos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hellocr.comun.CodigoError;
import com.hellocr.comun.ErrorNegocio;
import com.hellocr.conversaciones.GestionMiembros;
import com.hellocr.mensajes.EnvioMensajesService;
import com.hellocr.mensajes.HistorialService;
import com.hellocr.mensajes.MensajeDto;
import com.hellocr.mensajes.SolicitudEnvio;
import com.hellocr.soporte.PruebaIntegracion;
import com.hellocr.soporte.SesionPrueba;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

class MiembrosGrupoTest extends PruebaIntegracion {

    @Autowired
    private GrupoService grupos;
    @Autowired
    private HistorialService historial;
    @Autowired
    private EnvioMensajesService envio;
    @Autowired
    private GestionMiembros gestion;

    private SesionPrueba ana;
    private SesionPrueba luis;
    private SesionPrueba sofia;
    private UUID grupo;

    @BeforeEach
    void grupoDeAnaConLuis() throws Exception {
        ana = sesionDe("ana");
        luis = sesionDe("luis");
        sofia = sesionDe("sofia");
        grupo = grupos.crear(id(ana), new SolicitudGrupo("Familia", null, List.of(id(luis)))).id();
    }

    @Test
    void agregarAbreUnPeriodoDesdeElEvento() throws Exception {
        agregar(ana, sofia).andExpect(status().isNoContent());
        enviar(ana, "¡Bienvenida!");

        List<MensajeDto> deSofia = mensajes(sofia);
        assertThat(deSofia).extracting(MensajeDto::secuencia).containsExactly(2L, 3L);
        assertThat(deSofia.getFirst().evento().evento()).isEqualTo("MIEMBRO_AGREGADO");
        assertThat(deSofia.getFirst().evento().afectadoId()).isEqualTo(id(sofia));
        assertThat(mensajes(luis)).extracting(MensajeDto::secuencia).containsExactly(1L, 2L, 3L);
    }

    @Test
    void soloUnAdminAgregaYNoSePuedeRepetirNiPasarseDelMaximo() throws Exception {
        agregar(luis, sofia).andExpect(status().isForbidden()).andExpect(jsonPath("$.codigo").value("SIN_PERMISO"));
        agregar(ana, luis).andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("YA_ES_MIEMBRO"));
        mvc.perform(post("/api/grupos/" + grupo + "/miembros").with(con(ana))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"usuarioId\": \"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isNotFound());

        agregar(ana, sofia).andExpect(status().isNoContent());
        agregar(ana, sesionDe("marco")).andExpect(status().isNoContent());
        agregar(ana, sesionDe("elena")).andExpect(status().isNoContent());
        agregar(ana, sesionDe("diego"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("GRUPO_LLENO"))
                .andExpect(jsonPath("$.detail").value("El grupo ya tiene el máximo de 5 personas."));
    }

    @Test
    void quitarCierraElPeriodoEnElEventoYElQuitadoQuedaDeSoloLectura() throws Exception {
        quitar(ana, luis).andExpect(status().isNoContent());
        enviar(ana, "ya no lo ves");

        assertThat(mensajes(luis)).extracting(MensajeDto::secuencia).containsExactly(1L, 2L);
        assertThat(mensajes(luis).getLast().evento().evento()).isEqualTo("MIEMBRO_QUITADO");
        mvc.perform(get("/api/conversaciones/" + grupo).with(con(luis)))
                .andExpect(jsonPath("$.activa").value(false))
                .andExpect(jsonPath("$.miRol").value("MIEMBRO"));
        assertThatThrownBy(() -> enviar(luis, "hola")).isInstanceOfSatisfying(ErrorNegocio.class,
                error -> assertThat(error.codigo()).isEqualTo(CodigoError.NO_ES_MIEMBRO));
        mvc.perform(patch("/api/grupos/" + grupo).with(con(luis))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"nombre\": \"Otra\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("NO_ES_MIEMBRO"));
    }

    @Test
    void noSePuedeQuitarAUnoMismoNiAQuienNoEsta() throws Exception {
        quitar(ana, ana)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores.usuarioId").value("Para irte del grupo usá «Salir»."));
        quitar(ana, sofia)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Esa persona no está en el grupo."));
    }

    @Test
    void siSaleElUltimoAdminAsciendeElMiembroMasAntiguo() throws Exception {
        agregar(ana, sofia);

        salir(ana).andExpect(status().isNoContent());

        List<MensajeDto> deLuis = mensajes(luis);
        assertThat(deLuis).extracting(m -> m.evento().evento())
                .containsExactly("GRUPO_CREADO", "MIEMBRO_AGREGADO", "MIEMBRO_SALIO", "ADMIN_ASIGNADO");
        assertThat(deLuis.getLast().evento().afectadoId()).isEqualTo(id(luis));
        assertThat(deLuis.getLast().remitenteId()).isEqualTo(id(ana));
        assertThat(mensajes(ana)).extracting(MensajeDto::secuencia).containsExactly(1L, 2L, 3L);
        mvc.perform(get("/api/conversaciones/" + grupo).with(con(luis))).andExpect(jsonPath("$.miRol").value("ADMIN"));
        salir(ana).andExpect(status().isForbidden()).andExpect(jsonPath("$.codigo").value("NO_ES_MIEMBRO"));
    }

    @Test
    void siQuedanOtrosAdminsNadieAsciendeYSiSaleElUltimoMiembroNoPasaNada() throws Exception {
        hacerAdmin(ana, luis).andExpect(status().isNoContent());

        salir(ana).andExpect(status().isNoContent());
        salir(luis).andExpect(status().isNoContent());

        assertThat(mensajes(ana)).extracting(m -> m.evento().evento())
                .containsExactly("GRUPO_CREADO", "ADMIN_ASIGNADO", "MIEMBRO_SALIO");
        assertThat(mensajes(luis)).extracting(m -> m.evento().evento())
                .containsExactly("GRUPO_CREADO", "ADMIN_ASIGNADO", "MIEMBRO_SALIO", "MIEMBRO_SALIO");
        assertThat(gestion.contarActivos(grupo)).isZero();
    }

    @Test
    void hacerYQuitarAdminDejaSiempreAlMenosUno() throws Exception {
        hacerAdmin(ana, luis).andExpect(status().isNoContent());
        hacerAdmin(ana, luis).andExpect(status().isNoContent());
        quitarAdmin(ana, luis).andExpect(status().isNoContent());
        quitarAdmin(ana, ana)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("ULTIMO_ADMIN"));
        hacerAdmin(ana, sofia).andExpect(status().isNotFound());
        hacerAdmin(luis, luis).andExpect(status().isForbidden());

        assertThat(mensajes(ana)).extracting(m -> m.evento().evento())
                .containsExactly("GRUPO_CREADO", "ADMIN_ASIGNADO", "ADMIN_QUITADO");
    }

    @Test
    void quienVuelveAEntrarVeSoloSusPeriodos() throws Exception {
        quitar(ana, luis);
        enviar(ana, "mientras no estaba");
        agregar(ana, luis);
        enviar(ana, "de vuelta");

        assertThat(mensajes(luis)).extracting(MensajeDto::secuencia).containsExactly(1L, 2L, 4L, 5L);
        mvc.perform(get("/api/conversaciones/" + grupo).with(con(luis)))
                .andExpect(jsonPath("$.activa").value(true))
                .andExpect(jsonPath("$.miembros[1].usuario.nombreUsuario").value("luis"))
                .andExpect(jsonPath("$.miembros[1].periodos[0].desde").value(1))
                .andExpect(jsonPath("$.miembros[1].periodos[0].hasta").value(2))
                .andExpect(jsonPath("$.miembros[1].periodos[1].desde").value(4))
                .andExpect(jsonPath("$.miembros[1].periodos[1].hasta").value(nullValue()));
    }

    @Test
    void agregarEnParaleloNuncaPasaDelMaximo() throws Exception {
        List<Callable<Integer>> tareas = new ArrayList<>();
        for (String nombre : List.of("marco", "elena", "diego", "carla", "pablo")) {
            SesionPrueba invitado = sesionDe(nombre);
            tareas.add(() -> agregar(ana, invitado).andReturn().getResponse().getStatus());
        }

        List<Integer> estados = enParalelo(tareas);

        assertThat(Collections.frequency(estados, 204)).isEqualTo(3);
        assertThat(Collections.frequency(estados, 409)).isEqualTo(2);
        assertThat(gestion.contarActivos(grupo)).isEqualTo(5);
    }

    private List<MensajeDto> mensajes(SesionPrueba sesion) {
        return historial.pagina(id(sesion), grupo, null, null, null).mensajes();
    }

    private void enviar(SesionPrueba sesion, String texto) {
        envio.enviar(id(sesion), new SolicitudEnvio(UUID.randomUUID(), grupo, texto));
    }

    private ResultActions agregar(SesionPrueba actor, SesionPrueba invitado) throws Exception {
        return mvc.perform(post("/api/grupos/" + grupo + "/miembros").with(con(actor))
                .contentType(MediaType.APPLICATION_JSON).content("{\"usuarioId\": \"" + invitado.usuarioId() + "\"}"));
    }

    private ResultActions quitar(SesionPrueba actor, SesionPrueba quitado) throws Exception {
        return mvc.perform(delete("/api/grupos/" + grupo + "/miembros/" + quitado.usuarioId()).with(con(actor)));
    }

    private ResultActions salir(SesionPrueba actor) throws Exception {
        return mvc.perform(post("/api/grupos/" + grupo + "/salir").with(con(actor)));
    }

    private ResultActions hacerAdmin(SesionPrueba actor, SesionPrueba otro) throws Exception {
        return mvc.perform(put("/api/grupos/" + grupo + "/administradores/" + otro.usuarioId()).with(con(actor)));
    }

    private ResultActions quitarAdmin(SesionPrueba actor, SesionPrueba otro) throws Exception {
        return mvc.perform(delete("/api/grupos/" + grupo + "/administradores/" + otro.usuarioId()).with(con(actor)));
    }

    private static UUID id(SesionPrueba sesion) {
        return UUID.fromString(sesion.usuarioId());
    }
}
