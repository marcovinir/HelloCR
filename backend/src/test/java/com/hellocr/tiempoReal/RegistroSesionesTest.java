package com.hellocr.tiempoReal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RegistroSesionesTest {

    private static final Instant T0 = Instant.parse("2026-10-01T15:00:00Z");

    private final RegistroSesiones registro = new RegistroSesiones();
    private final UUID ana = UUID.randomUUID();
    private final UUID luis = UUID.randomUUID();

    @Test
    void laPrimeraSesionPoneEnLineaYLaUltimaDesconecta() {
        assertThat(registro.abrir("s1", ana, T0)).isTrue();
        assertThat(registro.abrir("s2", ana, T0)).isFalse();
        assertThat(registro.enLinea(ana)).isTrue();

        assertThat(registro.cerrar("s1")).isEmpty();
        assertThat(registro.enLinea(ana)).isTrue();
        assertThat(registro.cerrar("s2")).contains(ana);
        assertThat(registro.enLinea(ana)).isFalse();
        assertThat(registro.cerrar("desconocida")).isEmpty();
    }

    @Test
    void soloSeVencenLasSesionesConElTokenVencido() {
        registro.abrir("s1", ana, T0.plusSeconds(60));
        registro.abrir("s2", luis, T0.plusSeconds(600));

        assertThat(registro.vencidas(T0)).isEmpty();
        assertThat(registro.vencidas(T0.plusSeconds(60))).containsExactly("s1");
    }

    @Test
    void renovarSoloFuncionaParaLaDuenaDeLaSesion() {
        registro.abrir("s1", ana, T0);

        assertThat(registro.renovar("s1", luis, T0.plusSeconds(900))).isFalse();
        assertThat(registro.renovar("otra", ana, T0.plusSeconds(900))).isFalse();
        assertThat(registro.renovar("s1", ana, T0.plusSeconds(900))).isTrue();
        assertThat(registro.vencidas(T0.plusSeconds(60))).isEmpty();
    }

    @Test
    void listaLasSesionesDeCadaUsuario() {
        registro.abrir("s1", ana, T0);
        registro.abrir("s2", ana, T0);
        registro.abrir("s3", luis, T0);

        assertThat(registro.sesionesDe(ana)).containsExactlyInAnyOrder("s1", "s2");
        assertThat(registro.cantidad()).isEqualTo(3);
    }
}
