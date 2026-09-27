package com.hellocr.correo;

import static org.assertj.core.api.Assertions.assertThat;

import com.hellocr.usuarios.Usuario;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class PlantillasCorreoTest {

    private static final CorreoProperties PROPIEDADES = new CorreoProperties(CorreoProperties.Modo.CONSOLA,
            "HelloCR <no-responder@hellocr.local>", false, Duration.ofHours(24), Duration.ofHours(1),
            Duration.ofMinutes(1));

    private final PlantillasCorreo plantillas = new PlantillasCorreo("https://hellocr.duckdns.org/", PROPIEDADES);

    @Test
    void laVerificacionLlevaElEnlaceYCuantoDura() {
        CorreoSaliente correo = plantillas.verificacion(usuario("Sofía"), "tok_123");

        assertThat(correo.destinatario()).isEqualTo("sofia@correo.cr");
        assertThat(correo.asunto()).isEqualTo("Verificá tu correo en HelloCR");
        assertThat(correo.texto())
                .contains("¡Hola, Sofía!")
                .contains("https://hellocr.duckdns.org/verificar?token=tok_123")
                .contains("El enlace vence en 24 horas.");
        assertThat(correo.html())
                .contains("href=\"https://hellocr.duckdns.org/verificar?token=tok_123\"")
                .contains("Sofía");
    }

    @Test
    void laRecuperacionNombraLaCuentaYVenceEnUnaHora() {
        CorreoSaliente correo = plantillas.recuperacion(usuario("Sofía"), "tok_456");

        assertThat(correo.asunto()).isEqualTo("Restablecé tu contraseña de HelloCR");
        assertThat(correo.texto())
                .contains("@sofia")
                .contains("https://hellocr.duckdns.org/restablecer?token=tok_456")
                .contains("El enlace vence en 1 hora.")
                .contains("Si no fuiste vos, ignorá este correo");
    }

    @Test
    void elNombreVisibleSeEscapaEnElHtml() {
        CorreoSaliente correo = plantillas.verificacion(usuario("<b>Ana</b> & \"Luis\""), "tok");

        assertThat(correo.html())
                .contains("&lt;b&gt;Ana&lt;/b&gt; &amp; &quot;Luis&quot;")
                .doesNotContain("<b>Ana</b>");
        assertThat(correo.texto()).contains("¡Hola, <b>Ana</b> & \"Luis\"!");
    }

    @Test
    void describeLasDuracionesEnPalabras() {
        assertThat(PlantillasCorreo.describir(Duration.ofHours(24))).isEqualTo("24 horas");
        assertThat(PlantillasCorreo.describir(Duration.ofHours(1))).isEqualTo("1 hora");
        assertThat(PlantillasCorreo.describir(Duration.ofMinutes(90))).isEqualTo("90 minutos");
        assertThat(PlantillasCorreo.describir(Duration.ofMinutes(1))).isEqualTo("1 minuto");
    }

    private static Usuario usuario(String nombreVisible) {
        return new Usuario("sofia@correo.cr", "sofia", nombreVisible, "hash", "Ab3dEf7h", Instant.EPOCH);
    }
}
