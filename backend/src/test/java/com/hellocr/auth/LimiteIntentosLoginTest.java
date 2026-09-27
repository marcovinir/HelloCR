package com.hellocr.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hellocr.comun.CodigoError;
import com.hellocr.comun.ErrorNegocio;
import com.hellocr.soporte.RelojAjustable;
import java.time.Duration;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class LimiteIntentosLoginTest {

    private static final String CLAVE = "ana|127.0.0.1";

    private final RelojAjustable reloj = new RelojAjustable(ZoneId.of("America/Costa_Rica"));
    private final LimiteIntentosLogin limite =
            new LimiteIntentosLogin(new LoginProperties(5, Duration.ofMinutes(15)), reloj);

    @Test
    void cuatroFallosNoBloquean() {
        fallar(4);

        assertThatCode(() -> limite.verificar(CLAVE)).doesNotThrowAnyException();
    }

    @Test
    void elQuintoFalloBloqueaQuinceMinutos() {
        fallar(5);

        assertThatThrownBy(() -> limite.verificar(CLAVE)).isInstanceOfSatisfying(ErrorNegocio.class, error -> {
            assertThat(error.codigo()).isEqualTo(CodigoError.DEMASIADOS_INTENTOS);
            assertThat(error.extras()).containsEntry(ErrorNegocio.REINTENTAR_EN_SEGUNDOS, 900L);
            assertThat(error.getMessage()).isEqualTo("Demasiados intentos fallidos. Probá de nuevo en 15 minutos.");
        });

        reloj.avanzar(Duration.ofMinutes(14).plusSeconds(30));
        assertThatThrownBy(() -> limite.verificar(CLAVE))
                .hasMessage("Demasiados intentos fallidos. Probá de nuevo en 1 minuto.");

        reloj.avanzar(Duration.ofSeconds(30));
        assertThatCode(() -> limite.verificar(CLAVE)).doesNotThrowAnyException();
    }

    @Test
    void unExitoReiniciaElContador() {
        fallar(4);
        limite.registrarExito(CLAVE);
        fallar(4);

        assertThatCode(() -> limite.verificar(CLAVE)).doesNotThrowAnyException();
    }

    @Test
    void losFallosEspaciadosNoSeAcumulan() {
        fallar(4);
        reloj.avanzar(Duration.ofMinutes(15));
        fallar(4);

        assertThatCode(() -> limite.verificar(CLAVE)).doesNotThrowAnyException();
    }

    @Test
    void cadaClaveSeCuentaAparte() {
        fallar(5);

        assertThatCode(() -> limite.verificar("ana|10.0.0.2")).doesNotThrowAnyException();
    }

    @Test
    void laLimpiezaOlvidaLoQueYaVencio() {
        fallar(5);
        limite.registrarFallo("luis|127.0.0.1");
        reloj.avanzar(Duration.ofMinutes(16));

        limite.olvidarVencidos();

        assertThat(limite.registrados()).isZero();
    }

    @Test
    void olvidarUnaCuentaLevantaSusBloqueosPorCorreoYPorUsuarioSinTocarOtras() {
        fallar(5);
        for (int i = 0; i < 5; i++) {
            limite.registrarFallo("ana@correo.cr|10.0.0.2");
            limite.registrarFallo("anabel|127.0.0.1");
        }

        limite.olvidarCuenta("ana@correo.cr", "ana");

        assertThatCode(() -> limite.verificar(CLAVE)).doesNotThrowAnyException();
        assertThatCode(() -> limite.verificar("ana@correo.cr|10.0.0.2")).doesNotThrowAnyException();
        assertThatThrownBy(() -> limite.verificar("anabel|127.0.0.1")).isInstanceOf(ErrorNegocio.class);
    }

    private void fallar(int veces) {
        for (int i = 0; i < veces; i++) {
            limite.registrarFallo(CLAVE);
        }
    }
}
