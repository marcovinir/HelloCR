package com.hellocr.auth;

import static com.hellocr.auth.PropositoToken.RECUPERACION;
import static com.hellocr.auth.PropositoToken.VERIFICACION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hellocr.comun.CodigoError;
import com.hellocr.comun.ErrorNegocio;
import com.hellocr.soporte.PruebaIntegracion;
import com.hellocr.usuarios.Usuario;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class TokenCorreoServiceTest extends PruebaIntegracion {

    @Autowired
    private TokenCorreoService servicio;

    private Usuario ana;

    @BeforeEach
    void crearAna() {
        ana = crearUsuarioSinVerificar("ana");
    }

    @Test
    void emitirGuardaSoloElHashYVenceSegunElProposito() {
        String verificacion = servicio.emitir(ana, VERIFICACION);
        servicio.emitir(ana, RECUPERACION);

        assertThat(vigenciaEnSegundos(VERIFICACION)).isEqualTo(24 * 3600);
        assertThat(vigenciaEnSegundos(RECUPERACION)).isEqualTo(3600);
        assertThat(jdbc.queryForList("SELECT token_hash FROM tokens_correo", String.class))
                .doesNotContain(verificacion)
                .allSatisfy(hash -> assertThat(hash).hasSize(64));
    }

    @Test
    void consumirDevuelveElUsuarioYElTokenNoSirveDosVeces() {
        String token = servicio.emitir(ana, VERIFICACION);

        assertThat(servicio.consumir(token, VERIFICACION).getId()).isEqualTo(ana.getId());
        assertThatThrownBy(() -> servicio.consumir(token, VERIFICACION)).satisfies(this::esTokenInvalido);
    }

    @Test
    void unTokenVencidoNoSirve() {
        String token = servicio.emitir(ana, VERIFICACION);
        reloj.avanzar(Duration.ofHours(24));

        assertThatThrownBy(() -> servicio.consumir(token, VERIFICACION)).satisfies(this::esTokenInvalido);
    }

    @Test
    void unTokenDeOtroPropositoNoSirve() {
        String token = servicio.emitir(ana, RECUPERACION);

        assertThatThrownBy(() -> servicio.consumir(token, VERIFICACION)).satisfies(this::esTokenInvalido);
    }

    @Test
    void tokensInventadosOVaciosNoSirven() {
        assertThatThrownBy(() -> servicio.consumir("inventado", VERIFICACION)).satisfies(this::esTokenInvalido);
        assertThatThrownBy(() -> servicio.consumir("", VERIFICACION)).satisfies(this::esTokenInvalido);
        assertThatThrownBy(() -> servicio.consumir(null, VERIFICACION)).satisfies(this::esTokenInvalido);
    }

    @Test
    void emitirOtroInvalidaElAnteriorDelMismoProposito() {
        String viejo = servicio.emitir(ana, VERIFICACION);
        String recuperacion = servicio.emitir(ana, RECUPERACION);
        reloj.avanzar(Duration.ofMinutes(2));
        String nuevo = servicio.emitir(ana, VERIFICACION);

        assertThatThrownBy(() -> servicio.consumir(viejo, VERIFICACION)).satisfies(this::esTokenInvalido);
        assertThat(servicio.consumir(nuevo, VERIFICACION).getId()).isEqualTo(ana.getId());
        assertThat(servicio.consumir(recuperacion, RECUPERACION).getId()).isEqualTo(ana.getId());
    }

    @Test
    void soloSePuedeReenviarDespuesDeUnMinuto() {
        assertThat(servicio.puedeEmitir(ana, VERIFICACION)).isTrue();

        servicio.emitir(ana, VERIFICACION);

        assertThat(servicio.puedeEmitir(ana, VERIFICACION)).isFalse();
        assertThat(servicio.puedeEmitir(ana, RECUPERACION)).isTrue();
        reloj.avanzar(Duration.ofMinutes(1));
        assertThat(servicio.puedeEmitir(ana, VERIFICACION)).isTrue();
    }

    @Test
    void dosConsumosSimultaneosDelMismoTokenSoloUnoGana() throws Exception {
        String token = servicio.emitir(ana, RECUPERACION);

        List<Boolean> resultados = enParalelo(5, () -> {
            try {
                servicio.consumir(token, RECUPERACION);
                return true;
            } catch (ErrorNegocio error) {
                return false;
            }
        });

        assertThat(Collections.frequency(resultados, true)).isEqualTo(1);
    }

    @Test
    void laLimpiezaBorraSoloLosVencidos() {
        servicio.emitir(ana, RECUPERACION);
        servicio.emitir(ana, VERIFICACION);
        reloj.avanzar(Duration.ofHours(2));

        assertThat(servicio.limpiarVencidos()).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT proposito FROM tokens_correo", String.class))
                .containsExactly("VERIFICACION");
    }

    private long vigenciaEnSegundos(PropositoToken proposito) {
        return jdbc.queryForObject(
                "SELECT extract(epoch FROM expira_en - creado_en)::bigint FROM tokens_correo WHERE proposito = ?",
                Long.class, proposito.name());
    }

    private void esTokenInvalido(Throwable error) {
        assertThat(error).isInstanceOf(ErrorNegocio.class);
        assertThat(((ErrorNegocio) error).codigo()).isEqualTo(CodigoError.TOKEN_INVALIDO);
        assertThat(error.getMessage()).isEqualTo("El enlace no es válido o ya venció. Pedí uno nuevo.");
    }
}
