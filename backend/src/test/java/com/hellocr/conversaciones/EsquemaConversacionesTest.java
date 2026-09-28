package com.hellocr.conversaciones;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hellocr.soporte.PruebaIntegracion;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class EsquemaConversacionesTest extends PruebaIntegracion {

    private UUID ana;
    private UUID luis;

    @BeforeEach
    void crearUsuarios() {
        ana = crearUsuario("ana").getId();
        luis = crearUsuario("luis").getId();
    }

    @Test
    void unaConversacionNoPuedeSerGrupoYChatDirectoALaVez() {
        UUID directa = conversacion("DIRECTA");

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO grupos (conversacion_id, nombre, creado_por) VALUES (?, 'Familia', ?)", directa, ana))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void losPeriodosDeUnMiembroNoSeSolapan() {
        UUID grupo = conversacion("GRUPO");
        miembro(grupo, ana);
        periodoCerrado(grupo, ana, 1, 3);

        assertThatCode(() -> periodoAbierto(grupo, ana, 4)).doesNotThrowAnyException();
        assertThatThrownBy(() -> periodoAbierto(grupo, ana, 10)).hasMessageContaining("periodos_sin_solapamiento");
        assertThatThrownBy(() -> periodoCerrado(grupo, ana, 3, 3)).hasMessageContaining("periodos_sin_solapamiento");
    }

    @Test
    void laSecuenciaEsUnicaPorConversacionYElIdClientePorRemitente() {
        UUID grupo = conversacion("GRUPO");
        miembro(grupo, ana);
        UUID idCliente = UUID.randomUUID();
        texto(grupo, 1, ana, idCliente, "hola");

        assertThatThrownBy(() -> texto(grupo, 1, ana, UUID.randomUUID(), "otra"))
                .hasMessageContaining("mensajes_secuencia_unica");
        assertThatThrownBy(() -> texto(grupo, 2, ana, idCliente, "otra"))
                .hasMessageContaining("mensajes_id_cliente_unico");
    }

    @Test
    void soloUnMiembroPuedeSerRemitente() {
        UUID grupo = conversacion("GRUPO");

        assertThatThrownBy(() -> texto(grupo, 1, ana, UUID.randomUUID(), "hola"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void elTextoDependeDelTipoDeMensaje() {
        UUID grupo = conversacion("GRUPO");
        miembro(grupo, ana);

        assertThatThrownBy(() -> mensaje(grupo, 1, ana, "TEXTO", null))
                .hasMessageContaining("mensajes_texto_obligatorio");
        assertThatThrownBy(() -> mensaje(grupo, 1, ana, "EVENTO", "texto"))
                .hasMessageContaining("mensajes_evento_sin_texto");
        assertThatThrownBy(() -> mensaje(grupo, 1, ana, "TEXTO", "   "))
                .hasMessageContaining("mensajes_texto_no_vacio");
    }

    @Test
    void losEventosExigenAfectadoOValorSegunSuTipoYSoloCuelganDeMensajesEvento() {
        UUID grupo = conversacion("GRUPO");
        miembro(grupo, ana);
        long agregado = mensaje(grupo, 1, ana, "EVENTO", null);
        long renombre = mensaje(grupo, 2, ana, "EVENTO", null);
        long texto = texto(grupo, 3, ana, UUID.randomUUID(), "hola");

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO eventos_grupo (mensaje_id, evento) VALUES (?, 'MIEMBRO_AGREGADO')", agregado))
                .hasMessageContaining("eventos_afectado");
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO eventos_grupo (mensaje_id, evento) VALUES (?, 'NOMBRE_CAMBIADO')", renombre))
                .hasMessageContaining("eventos_valor");
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO eventos_grupo (mensaje_id, evento) VALUES (?, 'GRUPO_CREADO')", texto))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void unChatDirectoGuardaElParOrdenadoYSinRepetir() {
        UUID chat = conversacion("DIRECTA");
        miembro(chat, ana);
        miembro(chat, luis);
        UUID menor = jdbc.queryForObject("SELECT LEAST(?::uuid, ?::uuid)", UUID.class, ana, luis);
        UUID mayor = jdbc.queryForObject("SELECT GREATEST(?::uuid, ?::uuid)", UUID.class, ana, luis);

        assertThatThrownBy(() -> directo(chat, mayor, menor)).hasMessageContaining("chats_directos_orden");
        directo(chat, menor, mayor);

        UUID otro = conversacion("DIRECTA");
        miembro(otro, ana);
        miembro(otro, luis);
        assertThatThrownBy(() -> directo(otro, menor, mayor)).hasMessageContaining("chats_directos_par_unico");
    }

    @Test
    void laUltimaLeidaNoPuedeSuperarALaEntregada() {
        UUID chat = conversacion("DIRECTA");
        miembro(chat, ana);

        assertThatThrownBy(() -> jdbc.update("UPDATE miembros SET ultima_leida = 5 WHERE usuario_id = ?", ana))
                .hasMessageContaining("miembros_leida_no_supera_entregada");
    }

    private UUID conversacion(String tipo) {
        return jdbc.queryForObject("INSERT INTO conversaciones (tipo) VALUES (?) RETURNING id", UUID.class, tipo);
    }

    private void miembro(UUID conversacion, UUID usuario) {
        jdbc.update("INSERT INTO miembros (conversacion_id, usuario_id) VALUES (?, ?)", conversacion, usuario);
    }

    private void periodoAbierto(UUID conversacion, UUID usuario, long desde) {
        jdbc.update("""
                INSERT INTO periodos_membresia (conversacion_id, usuario_id, desde_secuencia) VALUES (?, ?, ?)
                """, conversacion, usuario, desde);
    }

    private void periodoCerrado(UUID conversacion, UUID usuario, long desde, long hasta) {
        jdbc.update("""
                INSERT INTO periodos_membresia (conversacion_id, usuario_id, desde_secuencia, hasta_secuencia)
                VALUES (?, ?, ?, ?)
                """, conversacion, usuario, desde, hasta);
    }

    private long texto(UUID conversacion, long secuencia, UUID remitente, UUID idCliente, String texto) {
        return jdbc.queryForObject("""
                INSERT INTO mensajes (conversacion_id, secuencia, remitente_id, id_cliente, tipo, texto)
                VALUES (?, ?, ?, ?, 'TEXTO', ?) RETURNING id
                """, Long.class, conversacion, secuencia, remitente, idCliente, texto);
    }

    private long mensaje(UUID conversacion, long secuencia, UUID remitente, String tipo, String texto) {
        return jdbc.queryForObject("""
                INSERT INTO mensajes (conversacion_id, secuencia, remitente_id, id_cliente, tipo, texto)
                VALUES (?, ?, ?, ?, ?, ?) RETURNING id
                """, Long.class, conversacion, secuencia, remitente, UUID.randomUUID(), tipo, texto);
    }

    private void directo(UUID conversacion, UUID usuarioA, UUID usuarioB) {
        jdbc.update("INSERT INTO chats_directos (conversacion_id, usuario_a_id, usuario_b_id) VALUES (?, ?, ?)",
                conversacion, usuarioA, usuarioB);
    }
}
