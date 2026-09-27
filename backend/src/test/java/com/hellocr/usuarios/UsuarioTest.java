package com.hellocr.usuarios;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class UsuarioTest {

    private static final Instant T0 = Instant.parse("2026-10-01T15:00:00Z");

    @Test
    void normalizaElCorreo() {
        assertThat(Usuario.normalizarCorreo("  Ana@Correo.CR ")).isEqualTo("ana@correo.cr");
    }

    @Test
    void normalizaElNombreDeUsuarioQuitandoLaArroba() {
        assertThat(Usuario.normalizarNombreUsuario(" @Marco_Rojas ")).isEqualTo("marco_rojas");
        assertThat(Usuario.normalizarNombreUsuario("ana")).isEqualTo("ana");
    }

    @Test
    void elConstructorNormalizaCorreoYNombreDeUsuario() {
        Usuario usuario = new Usuario(" Ana@Correo.CR", "@Ana", "Ana", "hash", "Ab3dEf7h", T0);

        assertThat(usuario.getCorreo()).isEqualTo("ana@correo.cr");
        assertThat(usuario.getNombreUsuario()).isEqualTo("ana");
    }

    @Test
    void laVerificacionConservaLaPrimeraFecha() {
        Usuario usuario = new Usuario("ana@correo.cr", "ana", "Ana", "hash", "Ab3dEf7h", T0);
        assertThat(usuario.correoVerificado()).isFalse();

        usuario.verificarCorreo(T0.plusSeconds(60));
        usuario.verificarCorreo(T0.plusSeconds(120));

        assertThat(usuario.correoVerificado()).isTrue();
        assertThat(usuario.getCorreoVerificadoEn()).isEqualTo(T0.plusSeconds(60));
    }

    @Test
    void reconoceLosNombresReservados() {
        assertThat(NombresReservados.contiene("admin")).isTrue();
        assertThat(NombresReservados.contiene("soporte")).isTrue();
        assertThat(NombresReservados.contiene("ana")).isFalse();
    }
}
