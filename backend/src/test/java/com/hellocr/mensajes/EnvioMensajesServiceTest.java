package com.hellocr.mensajes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hellocr.comun.CodigoError;
import com.hellocr.comun.ErrorNegocio;
import com.hellocr.conversaciones.ChatDirectoService;
import com.hellocr.soporte.PruebaIntegracion;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

@RecordApplicationEvents
class EnvioMensajesServiceTest extends PruebaIntegracion {

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
    void abrirChat() {
        ana = crearUsuario("ana").getId();
        luis = crearUsuario("luis").getId();
        chat = chats.abrir(ana, luis).detalle().id();
    }

    @Test
    void enviarAsignaSecuenciasConsecutivasYAvanzaLasMarcasDelRemitente() {
        MensajeDto primero = enviar(ana, "Hola").mensaje();
        MensajeDto segundo = enviar(luis, "¡Buenas!").mensaje();

        assertThat(primero.secuencia()).isEqualTo(1);
        assertThat(primero.tipo()).isEqualTo(TipoMensaje.TEXTO);
        assertThat(primero.remitenteId()).isEqualTo(ana);
        assertThat(primero.conversacionId()).isEqualTo(chat);
        assertThat(primero.creadoEn()).isEqualTo(reloj.instant());
        assertThat(segundo.secuencia()).isEqualTo(2);
        assertThat(marcas(ana)).containsEntry("ultima_entregada", 1L).containsEntry("ultima_leida", 1L);
        assertThat(marcas(luis)).containsEntry("ultima_entregada", 2L).containsEntry("ultima_leida", 2L);
        assertThat(eventos.stream(MensajeEnviado.class)).extracting(evento -> evento.mensaje().secuencia())
                .containsExactly(1L, 2L);
    }

    @Test
    void elTextoSeGuardaSinEspaciosAlrededor() {
        assertThat(enviar(ana, "  hola \n").mensaje().texto()).isEqualTo("hola");
        assertThat(jdbc.queryForObject("SELECT texto FROM mensajes", String.class)).isEqualTo("hola");
    }

    @Test
    void reenviarElMismoIdClienteDevuelveElOriginalSinPublicarlo() {
        UUID idCliente = UUID.randomUUID();
        envio.enviar(ana, new SolicitudEnvio(idCliente, chat, "original"));
        enviar(luis, "otro");

        EnvioMensajesService.Resultado repetido = envio.enviar(ana, new SolicitudEnvio(idCliente, chat, "reintento"));

        assertThat(repetido.nuevo()).isFalse();
        assertThat(repetido.mensaje().secuencia()).isEqualTo(1);
        assertThat(repetido.mensaje().texto()).isEqualTo("original");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM mensajes", Integer.class)).isEqualTo(2);
        assertThat(eventos.stream(MensajeEnviado.class)).hasSize(2);
    }

    @Test
    void unaConversacionInexistenteOAjenaSeRechaza() {
        UUID sofia = crearUsuario("sofia").getId();

        assertThatThrownBy(() -> envio.enviar(ana, new SolicitudEnvio(UUID.randomUUID(), UUID.randomUUID(), "hola")))
                .satisfies(codigo(CodigoError.NO_ENCONTRADO));
        assertThatThrownBy(() -> enviar(sofia, "hola")).satisfies(codigo(CodigoError.NO_ES_MIEMBRO));
    }

    @Test
    void losMensajesVaciosOConCaracteresNulosSonInvalidos() {
        for (String texto : Arrays.asList("   ", "", null, "hola\u0000mundo")) {
            assertThatThrownBy(() -> enviar(ana, texto)).as(String.valueOf(texto))
                    .satisfies(codigo(CodigoError.VALIDACION));
        }
        assertThatThrownBy(() -> envio.enviar(ana, new SolicitudEnvio(null, chat, "hola")))
                .satisfies(codigo(CodigoError.VALIDACION));
        assertThatThrownBy(() -> envio.enviar(ana, new SolicitudEnvio(UUID.randomUUID(), null, "hola")))
                .satisfies(codigo(CodigoError.VALIDACION));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM mensajes", Integer.class)).isZero();
    }

    @Test
    void unMensajeDe4096EmojisEntraYUnoMasEsDemasiadoLargo() {
        String emojis = "😀".repeat(4096);

        assertThat(enviar(ana, emojis).mensaje().texto()).isEqualTo(emojis);
        assertThat(jdbc.queryForObject("SELECT length(texto) FROM mensajes", Integer.class)).isEqualTo(4096);
        assertThatThrownBy(() -> enviar(ana, emojis + "😀")).satisfies(codigo(CodigoError.TEXTO_MUY_LARGO));
        assertThatThrownBy(() -> enviar(ana, "a".repeat(4097))).satisfies(codigo(CodigoError.TEXTO_MUY_LARGO));
    }

    @Test
    void masDeTreintaMensajesEnDiezSegundosSeFrenan() {
        for (int i = 1; i <= 30; i++) {
            enviar(ana, "mensaje " + i);
        }

        assertThatThrownBy(() -> enviar(ana, "uno más")).satisfies(codigo(CodigoError.DEMASIADOS_MENSAJES));
        reloj.avanzar(Duration.ofSeconds(10));
        assertThat(enviar(ana, "uno más").mensaje().secuencia()).isEqualTo(31);
    }

    @Test
    void veinteEnviosSimultaneosRecibenLasSecuenciasDel1Al20() throws Exception {
        List<Long> secuencias = enParalelo(20, () -> enviar(ana, "hola").mensaje().secuencia());

        assertThat(secuencias).containsExactlyInAnyOrderElementsOf(LongStream.rangeClosed(1, 20).boxed().toList());
    }

    @Test
    void elMismoIdClienteCincoVecesALaVezCreaUnSoloMensaje() throws Exception {
        UUID idCliente = UUID.randomUUID();

        List<EnvioMensajesService.Resultado> resultados =
                enParalelo(5, () -> envio.enviar(ana, new SolicitudEnvio(idCliente, chat, "hola")));

        assertThat(resultados).extracting(resultado -> resultado.mensaje().secuencia()).containsOnly(1L);
        assertThat(resultados).filteredOn(EnvioMensajesService.Resultado::nuevo).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM mensajes", Integer.class)).isEqualTo(1);
    }

    private EnvioMensajesService.Resultado enviar(UUID remitente, String texto) {
        return envio.enviar(remitente, new SolicitudEnvio(UUID.randomUUID(), chat, texto));
    }

    private java.util.Map<String, Object> marcas(UUID usuario) {
        return jdbc.queryForMap("SELECT ultima_entregada, ultima_leida FROM miembros WHERE conversacion_id = ? "
                + "AND usuario_id = ?", chat, usuario);
    }

    private static Consumer<Throwable> codigo(CodigoError esperado) {
        return error -> assertThat(error).isInstanceOfSatisfying(ErrorNegocio.class,
                negocio -> assertThat(negocio.codigo()).isEqualTo(esperado));
    }
}
