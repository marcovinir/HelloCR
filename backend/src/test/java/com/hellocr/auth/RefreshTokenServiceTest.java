package com.hellocr.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hellocr.comun.CodigoError;
import com.hellocr.comun.ErrorNegocio;
import com.hellocr.soporte.PruebaIntegracion;
import com.hellocr.usuarios.Usuario;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

@RecordApplicationEvents
class RefreshTokenServiceTest extends PruebaIntegracion {

    @Autowired
    private RefreshTokenService servicio;
    @Autowired
    private ApplicationEvents eventos;

    private Usuario ana;

    @BeforeEach
    void crearAna() {
        ana = crearUsuario("ana");
    }

    @Test
    void rotarDevuelveElUsuarioYUnTokenNuevo() {
        String original = servicio.emitir(ana);

        RefreshTokenService.Rotacion rotacion = servicio.rotar(original);

        assertThat(rotacion.usuario().getId()).isEqualTo(ana.getId());
        assertThat(rotacion.nuevoToken()).isNotBlank().isNotEqualTo(original);
        assertThat(jdbc.queryForObject("SELECT token_hash FROM refresh_tokens ORDER BY id LIMIT 1", String.class))
                .as("en la base solo se guarda el hash")
                .isNotEqualTo(original)
                .hasSize(64);
    }

    @Test
    void cadaRotacionExtiendeLaSesion30Dias() {
        String token = servicio.emitir(ana);
        reloj.avanzar(Duration.ofDays(29));
        String renovado = servicio.rotar(token).nuevoToken();
        reloj.avanzar(Duration.ofDays(29));

        assertThat(servicio.rotar(renovado).nuevoToken()).isNotBlank();
    }

    @Test
    void unTokenVencidoNoSirve() {
        String token = servicio.emitir(ana);
        reloj.avanzar(Duration.ofDays(30));

        assertThatThrownBy(() -> servicio.rotar(token)).satisfies(this::esSesionInvalida);
    }

    @Test
    void reusarUnTokenRotadoDespuesDeLaGraciaCierraTodasLasSesiones() {
        String sesionA = servicio.emitir(ana);
        String sesionB = servicio.emitir(ana);
        String sesionA2 = servicio.rotar(sesionA).nuevoToken();
        reloj.avanzar(Duration.ofSeconds(31));

        assertThatThrownBy(() -> servicio.rotar(sesionA)).satisfies(this::esSesionInvalida);

        assertThatThrownBy(() -> servicio.rotar(sesionA2)).satisfies(this::esSesionInvalida);
        assertThatThrownBy(() -> servicio.rotar(sesionB)).satisfies(this::esSesionInvalida);
        assertThat(eventos.stream(SesionesRevocadas.class)).containsExactly(new SesionesRevocadas(ana.getId()));
    }

    @Test
    void reusarDentroDeLaGraciaSoloRechazaEseIntento() {
        String sesionA = servicio.emitir(ana);
        String sesionA2 = servicio.rotar(sesionA).nuevoToken();
        reloj.avanzar(Duration.ofSeconds(5));

        assertThatThrownBy(() -> servicio.rotar(sesionA)).satisfies(this::esSesionInvalida);

        assertThat(servicio.rotar(sesionA2).nuevoToken()).isNotBlank();
        assertThat(eventos.stream(SesionesRevocadas.class)).isEmpty();
    }

    @Test
    void unTokenDesconocidoOVacioNoSirve() {
        assertThatThrownBy(() -> servicio.rotar("inventado")).satisfies(this::esSesionInvalida);
        assertThatThrownBy(() -> servicio.rotar(null)).satisfies(this::esSesionInvalida);
    }

    @Test
    void unTokenRevocadoPorLogoutNoSirve() {
        String token = servicio.emitir(ana);

        servicio.revocar(token);

        assertThatThrownBy(() -> servicio.rotar(token)).satisfies(this::esSesionInvalida);
    }

    @Test
    void revocarTodosCierraTodasLasSesionesYAvisa() {
        String sesionA = servicio.emitir(ana);
        String sesionB = servicio.emitir(ana);

        servicio.revocarTodos(ana.getId());

        assertThatThrownBy(() -> servicio.rotar(sesionA)).satisfies(this::esSesionInvalida);
        assertThatThrownBy(() -> servicio.rotar(sesionB)).satisfies(this::esSesionInvalida);
        assertThat(eventos.stream(SesionesRevocadas.class)).contains(new SesionesRevocadas(ana.getId()));
    }

    @Test
    void laLimpiezaBorraSoloLosVencidos() {
        servicio.emitir(ana);
        reloj.avanzar(Duration.ofDays(29));
        servicio.emitir(ana);
        reloj.avanzar(Duration.ofDays(2));

        assertThat(servicio.limpiarVencidos()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_tokens", Integer.class)).isEqualTo(1);
    }

    private void esSesionInvalida(Throwable error) {
        assertThat(error).isInstanceOf(ErrorNegocio.class);
        assertThat(((ErrorNegocio) error).codigo()).isEqualTo(CodigoError.NO_AUTENTICADO);
    }
}
