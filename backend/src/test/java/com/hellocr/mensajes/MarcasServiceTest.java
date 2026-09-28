package com.hellocr.mensajes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hellocr.comun.CodigoError;
import com.hellocr.comun.ErrorNegocio;
import com.hellocr.conversaciones.ChatDirectoService;
import com.hellocr.soporte.PruebaIntegracion;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

@RecordApplicationEvents
class MarcasServiceTest extends PruebaIntegracion {

    @Autowired
    private MarcasService marcasService;
    @Autowired
    private EnvioMensajesService envio;
    @Autowired
    private ChatDirectoService chats;
    @Autowired
    private ApplicationEvents eventos;

    private UUID ana;
    private UUID luis;
    private UUID chat;

    @BeforeEach
    void chatConTresMensajesDeAna() {
        ana = crearUsuario("ana").getId();
        luis = crearUsuario("luis").getId();
        chat = chats.abrir(ana, luis).detalle().id();
        for (int i = 1; i <= 3; i++) {
            envio.enviar(ana, new SolicitudEnvio(UUID.randomUUID(), chat, "mensaje " + i));
        }
    }

    @Test
    void entregadosAvanzaLaMarcaYAvisa() {
        marcasService.entregados(luis, chat, 2L);

        assertThat(marcas(luis)).containsEntry("ultima_entregada", 2L).containsEntry("ultima_leida", 0L);
        assertThat(eventos.stream(EstadoActualizado.class)).containsExactly(new EstadoActualizado(chat, luis, 2, 0));
    }

    @Test
    void leidosTambienMarcaComoEntregado() {
        marcasService.leidos(luis, chat, 3L);

        assertThat(marcas(luis)).containsEntry("ultima_entregada", 3L).containsEntry("ultima_leida", 3L);
        assertThat(eventos.stream(EstadoActualizado.class)).containsExactly(new EstadoActualizado(chat, luis, 3, 3));
    }

    @Test
    void lasMarcasNuncaBajanYSinCambiosNoHayAviso() {
        marcasService.leidos(luis, chat, 3L);

        marcasService.entregados(luis, chat, 1L);
        marcasService.leidos(luis, chat, 2L);
        marcasService.leidos(luis, chat, 0L);

        assertThat(marcas(luis)).containsEntry("ultima_entregada", 3L).containsEntry("ultima_leida", 3L);
        assertThat(eventos.stream(EstadoActualizado.class)).hasSize(1);
    }

    @Test
    void unAcuseMasAllaDeLoQueExisteSeRecorta() {
        marcasService.entregados(luis, chat, 999L);
        assertThat(marcas(luis)).containsEntry("ultima_entregada", 3L).containsEntry("ultima_leida", 0L);

        marcasService.leidos(luis, chat, Long.MAX_VALUE);
        assertThat(marcas(luis)).containsEntry("ultima_entregada", 3L).containsEntry("ultima_leida", 3L);
    }

    @Test
    void faltanDatosOQuienNoEsMiembroActivoSonErrores() {
        UUID sofia = crearUsuario("sofia").getId();

        assertThatThrownBy(() -> marcasService.leidos(luis, chat, null)).satisfies(codigo(CodigoError.VALIDACION));
        assertThatThrownBy(() -> marcasService.leidos(luis, null, 1L)).satisfies(codigo(CodigoError.VALIDACION));
        assertThatThrownBy(() -> marcasService.leidos(sofia, chat, 1L)).satisfies(codigo(CodigoError.NO_ES_MIEMBRO));
        assertThatThrownBy(() -> marcasService.leidos(luis, UUID.randomUUID(), 1L))
                .satisfies(codigo(CodigoError.NO_ES_MIEMBRO));
    }

    private Map<String, Object> marcas(UUID usuario) {
        return jdbc.queryForMap("SELECT ultima_entregada, ultima_leida FROM miembros WHERE conversacion_id = ? "
                + "AND usuario_id = ?", chat, usuario);
    }

    private static Consumer<Throwable> codigo(CodigoError esperado) {
        return error -> assertThat(error).isInstanceOfSatisfying(ErrorNegocio.class,
                negocio -> assertThat(negocio.codigo()).isEqualTo(esperado));
    }
}
