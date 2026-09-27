package com.hellocr.usuarios;

import static org.assertj.core.api.Assertions.assertThat;

import com.hellocr.soporte.PruebaIntegracion;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class LimpiezaCuentasTest extends PruebaIntegracion {

    @Autowired
    private LimpiezaCuentas limpieza;

    @Test
    void borraLasCuentasSinVerificarDeMasDeSieteDias() {
        crearUsuarioSinVerificar("vieja");
        crearUsuario("verificada");
        reloj.avanzar(Duration.ofDays(3));
        crearUsuarioSinVerificar("nueva");
        reloj.avanzar(Duration.ofDays(4).plusMinutes(1));

        assertThat(limpieza.borrarSinVerificar()).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT nombre_usuario FROM usuarios ORDER BY nombre_usuario", String.class))
                .containsExactly("nueva", "verificada");
    }
}
