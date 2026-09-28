package com.hellocr.mensajes;

import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hellocr.conversaciones.ChatDirectoService;
import com.hellocr.conversaciones.ConversacionRepository;
import com.hellocr.conversaciones.GestionMiembros;
import com.hellocr.conversaciones.Rol;
import com.hellocr.conversaciones.TipoConversacion;
import com.hellocr.soporte.PruebaIntegracion;
import com.hellocr.soporte.SesionPrueba;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

class HistorialControllerTest extends PruebaIntegracion {

    @Autowired
    private ChatDirectoService chats;
    @Autowired
    private EnvioMensajesService envio;
    @Autowired
    private ConversacionRepository conversaciones;
    @Autowired
    private GestionMiembros gestion;

    private SesionPrueba ana;
    private SesionPrueba luis;
    private UUID chat;

    @BeforeEach
    void chatConDoceMensajes() throws Exception {
        ana = sesionDe("ana");
        luis = sesionDe("luis");
        chat = chats.abrir(id(ana), id(luis)).detalle().id();
        for (int i = 1; i <= 12; i++) {
            enviar(id(ana), chat, "m" + i);
        }
    }

    @Test
    void sinParametrosDevuelveLosMasRecientesEnOrdenAscendente() throws Exception {
        pagina(luis, chat, "?limite=5")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mensajes[*].secuencia").value(contains(8, 9, 10, 11, 12)))
                .andExpect(jsonPath("$.mensajes[0].texto").value("m8"))
                .andExpect(jsonPath("$.mensajes[0].tipo").value("TEXTO"))
                .andExpect(jsonPath("$.mensajes[0].remitenteId").value(ana.usuarioId()))
                .andExpect(jsonPath("$.mensajes[0].creadoEn").value("2026-10-01T15:00:00Z"))
                .andExpect(jsonPath("$.hayMas").value(true));
    }

    @Test
    void antesDeTraeLaPaginaAnterior() throws Exception {
        pagina(luis, chat, "?antesDe=8&limite=5")
                .andExpect(jsonPath("$.mensajes[*].secuencia").value(contains(3, 4, 5, 6, 7)))
                .andExpect(jsonPath("$.hayMas").value(true));
        pagina(luis, chat, "?antesDe=3&limite=5")
                .andExpect(jsonPath("$.mensajes[*].secuencia").value(contains(1, 2)))
                .andExpect(jsonPath("$.hayMas").value(false));
    }

    @Test
    void despuesDeTraeLoQueFalta() throws Exception {
        pagina(luis, chat, "?despuesDe=10")
                .andExpect(jsonPath("$.mensajes[*].secuencia").value(contains(11, 12)))
                .andExpect(jsonPath("$.hayMas").value(false));
        pagina(luis, chat, "?despuesDe=0&limite=5")
                .andExpect(jsonPath("$.mensajes[*].secuencia").value(contains(1, 2, 3, 4, 5)))
                .andExpect(jsonPath("$.hayMas").value(true));
    }

    @Test
    void porDefectoTraeHasta50() throws Exception {
        pagina(luis, chat, "")
                .andExpect(jsonPath("$.mensajes.length()").value(12))
                .andExpect(jsonPath("$.hayMas").value(false));
    }

    @Test
    void losParametrosInvalidosSon400() throws Exception {
        pagina(luis, chat, "?antesDe=5&despuesDe=1")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores.antesDe").value("Pedí mensajes antesDe o despuesDe, no los dos."));
        pagina(luis, chat, "?limite=0")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores.limite").value("El límite tiene que estar entre 1 y 200."));
        pagina(luis, chat, "?limite=201").andExpect(status().isBadRequest());
        pagina(luis, chat, "?antesDe=abc").andExpect(status().isBadRequest());
    }

    @Test
    void cadaUnoVeSoloLoDeSusPeriodosYQuienNuncaFueMiembroRecibe404() throws Exception {
        SesionPrueba sofia = sesionDe("sofia");
        UUID grupo = conversaciones.crear(TipoConversacion.GRUPO, reloj.instant());
        gestion.incorporar(grupo, id(ana), Rol.ADMIN, 1);
        enviarVarios(grupo, 3);
        gestion.incorporar(grupo, id(luis), Rol.MIEMBRO, 4);
        enviarVarios(grupo, 3);
        gestion.retirar(grupo, id(luis), 5);
        enviarVarios(grupo, 2);

        pagina(luis, grupo, "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mensajes[*].secuencia").value(contains(4, 5)));
        pagina(ana, grupo, "").andExpect(jsonPath("$.mensajes.length()").value(8));
        pagina(sofia, grupo, "")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value("NO_ENCONTRADO"));
    }

    private ResultActions pagina(SesionPrueba sesion, UUID conversacion, String consulta) throws Exception {
        return mvc.perform(get("/api/conversaciones/" + conversacion + "/mensajes" + consulta).with(con(sesion)));
    }

    private void enviarVarios(UUID conversacion, int cantidad) {
        for (int i = 0; i < cantidad; i++) {
            enviar(id(ana), conversacion, "hola");
        }
    }

    private void enviar(UUID remitente, UUID conversacion, String texto) {
        envio.enviar(remitente, new SolicitudEnvio(UUID.randomUUID(), conversacion, texto));
    }

    private static UUID id(SesionPrueba sesion) {
        return UUID.fromString(sesion.usuarioId());
    }
}
