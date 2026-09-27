package com.hellocr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hellocr.soporte.PruebaIntegracion;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class EsquemaTest extends PruebaIntegracion {

    @Test
    void losIdsDeUsuarioSonUuidVersion7() {
        UUID id = insertarUsuario("ana@correo.cr", "ana", "Ab3dEf7h");

        assertThat(id.version()).isEqualTo(7);
    }

    @Test
    void elCorreoDebeGuardarseNormalizado() {
        assertThatThrownBy(() -> insertarUsuario("Ana@Correo.cr", "ana", "Ab3dEf7h"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("usuarios_correo_normalizado");
    }

    @Test
    void elNombreDeUsuarioRespetaElFormato() {
        for (String invalido : List.of("Ana", "1ana", "an", "ana-maria", "_ana")) {
            assertThatThrownBy(() -> insertarUsuario("x@correo.cr", invalido, "Ab3dEf7h"))
                    .as(invalido)
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("usuarios_nombre_usuario_formato");
        }
        assertThatCode(() -> insertarUsuario("x@correo.cr", "ana.maria_2", "Ab3dEf7h")).doesNotThrowAnyException();
    }

    @Test
    void elCodigoDeInvitacionNoAdmiteCaracteresAmbiguos() {
        assertThatThrownBy(() -> insertarUsuario("ana@correo.cr", "ana", "O0Il1abc"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("usuarios_codigo_invitacion_formato");
    }

    @Test
    void borrarUnUsuarioBorraSusTokens() {
        UUID id = insertarUsuario("ana@correo.cr", "ana", "Ab3dEf7h");
        jdbc.update("INSERT INTO refresh_tokens (usuario_id, token_hash, expira_en) VALUES (?, repeat('a', 64), now())", id);
        jdbc.update("""
                INSERT INTO tokens_correo (usuario_id, proposito, token_hash, expira_en)
                VALUES (?, 'VERIFICACION', repeat('b', 64), now())
                """, id);

        jdbc.update("DELETE FROM usuarios WHERE id = ?", id);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_tokens", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tokens_correo", Integer.class)).isZero();
    }

    private UUID insertarUsuario(String correo, String nombreUsuario, String codigo) {
        return jdbc.queryForObject("""
                INSERT INTO usuarios (correo, nombre_usuario, nombre_visible, hash_contrasena, codigo_invitacion)
                VALUES (?, ?, 'Ana', 'x', ?) RETURNING id
                """, UUID.class, correo, nombreUsuario, codigo);
    }
}
