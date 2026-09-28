package com.hellocr.conversaciones;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hellocr.grupos.GrupoService;
import com.hellocr.grupos.MiembrosGrupoService;
import com.hellocr.grupos.SolicitudGrupo;
import com.hellocr.mensajes.EnvioMensajesService;
import com.hellocr.mensajes.MarcasService;
import com.hellocr.mensajes.SolicitudEnvio;
import com.hellocr.soporte.PruebaIntegracion;
import com.hellocr.soporte.SesionPrueba;
import com.hellocr.tiempoReal.RegistroSesiones;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

class ListaConversacionesTest extends PruebaIntegracion {

    @Autowired
    private ChatDirectoService chats;
    @Autowired
    private EnvioMensajesService envio;
    @Autowired
    private MarcasService marcas;
    @Autowired
    private GrupoService grupos;
    @Autowired
    private MiembrosGrupoService miembros;
    @Autowired
    private RegistroSesiones registroSesiones;

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
    void ordenaPorElUltimoMensajeEIncluyeChatsYGrupos() throws Exception {
        UUID conLuis = abrir(ana, luis);
        UUID conSofia = abrir(ana, sofia);
        grupos.crear(id(ana), new SolicitudGrupo("Familia", null, List.of(id(luis), id(sofia))));
        reloj.avanzar(Duration.ofMinutes(1));
        enviar(luis, conLuis, "hola");
        reloj.avanzar(Duration.ofMinutes(1));
        enviar(sofia, conSofia, "buenas");

        lista(ana)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].titulo").value(contains("Usuario sofia", "Usuario luis", "Familia")))
                .andExpect(jsonPath("$[2].tipo").value("GRUPO"))
                .andExpect(jsonPath("$[2].otroUsuario").value(nullValue()))
                .andExpect(jsonPath("$[2].ultimoMensaje.evento.evento").value("GRUPO_CREADO"))
                .andExpect(jsonPath("$[0].ultimoMensaje.texto").value("buenas"));
    }

    @Test
    void elChatDirectoTraeALaOtraPersonaConSuPresencia() throws Exception {
        abrir(ana, luis);
        jdbc.update("UPDATE usuarios SET ultima_conexion = '2026-09-30T20:00:00Z' WHERE nombre_usuario = 'luis'");

        lista(ana)
                .andExpect(jsonPath("$[0].titulo").value("Usuario luis"))
                .andExpect(jsonPath("$[0].otroUsuario.nombreUsuario").value("luis"))
                .andExpect(jsonPath("$[0].otroUsuario.enLinea").value(false))
                .andExpect(jsonPath("$[0].otroUsuario.ultimaConexion").value("2026-09-30T20:00:00Z"))
                .andExpect(jsonPath("$[0].otroUsuario.correo").doesNotExist())
                .andExpect(jsonPath("$[0].ultimoMensaje").value(nullValue()))
                .andExpect(jsonPath("$[0].ultimaSecuencia").value(0))
                .andExpect(jsonPath("$[0].noLeidos").value(0))
                .andExpect(jsonPath("$[0].activa").value(true));

        registroSesiones.abrir("sesion-de-prueba", id(luis), reloj.instant().plus(Duration.ofMinutes(15)));
        try {
            lista(ana).andExpect(jsonPath("$[0].otroUsuario.enLinea").value(true));
        } finally {
            registroSesiones.cerrar("sesion-de-prueba");
        }
    }

    @Test
    void noLeidosCuentaSoloMensajesAjenosPosterioresALaUltimaLeida() throws Exception {
        UUID chat = abrir(ana, luis);
        for (int i = 1; i <= 3; i++) {
            enviar(luis, chat, "mensaje " + i);
        }

        lista(ana).andExpect(jsonPath("$[0].noLeidos").value(3)).andExpect(jsonPath("$[0].ultimaSecuencia").value(3));
        marcas.leidos(id(ana), chat, 2L);
        lista(ana).andExpect(jsonPath("$[0].noLeidos").value(1));
        enviar(ana, chat, "ya leí todo");
        lista(ana).andExpect(jsonPath("$[0].noLeidos").value(0)).andExpect(jsonPath("$[0].ultimaSecuencia").value(4));
        lista(luis).andExpect(jsonPath("$[0].noLeidos").value(1));
    }

    @Test
    void unExMiembroVeElGrupoInactivoYSoloHastaSuSalida() throws Exception {
        UUID grupo = grupos.crear(id(ana), new SolicitudGrupo("Familia", null, List.of(id(luis)))).id();
        miembros.quitar(id(ana), grupo, id(luis));
        enviar(ana, grupo, "ya no lo ves");

        lista(luis)
                .andExpect(jsonPath("$[0].activa").value(false))
                .andExpect(jsonPath("$[0].ultimaSecuencia").value(2))
                .andExpect(jsonPath("$[0].ultimoMensaje.evento.evento").value("MIEMBRO_QUITADO"))
                .andExpect(jsonPath("$[0].noLeidos").value(0));
        lista(ana)
                .andExpect(jsonPath("$[0].activa").value(true))
                .andExpect(jsonPath("$[0].ultimaSecuencia").value(3));
    }

    private ResultActions lista(SesionPrueba sesion) throws Exception {
        return mvc.perform(get("/api/conversaciones").with(con(sesion)));
    }

    private UUID abrir(SesionPrueba sesion, SesionPrueba otra) {
        return chats.abrir(id(sesion), id(otra)).detalle().id();
    }

    private void enviar(SesionPrueba sesion, UUID conversacion, String texto) {
        envio.enviar(id(sesion), new SolicitudEnvio(UUID.randomUUID(), conversacion, texto));
    }

    private static UUID id(SesionPrueba sesion) {
        return UUID.fromString(sesion.usuarioId());
    }
}
