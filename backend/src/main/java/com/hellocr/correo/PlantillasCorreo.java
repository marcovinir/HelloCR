package com.hellocr.correo;

import com.hellocr.usuarios.Usuario;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

/** Textos de los correos de la cuenta, en voseo tico. */
@Component
public class PlantillasCorreo {

    private final String urlPublica;
    private final CorreoProperties propiedades;

    public PlantillasCorreo(@Value("${app.url-publica}") String urlPublica, CorreoProperties propiedades) {
        this.urlPublica = urlPublica.endsWith("/") ? urlPublica.substring(0, urlPublica.length() - 1) : urlPublica;
        this.propiedades = propiedades;
    }

    public CorreoSaliente verificacion(Usuario usuario, String token) {
        String enlace = urlPublica + "/verificar?token=" + token;
        String vence = describir(propiedades.duracionVerificacion());
        String texto = """
                ¡Hola, %s!

                Para terminar de crear tu cuenta en HelloCR, abrí este enlace:
                %s

                El enlace vence en %s. Si no creaste esta cuenta, ignorá este correo.
                """.formatted(usuario.getNombreVisible(), enlace, vence);
        String html = """
                <p>¡Hola, %s!</p>
                <p>Para terminar de crear tu cuenta en HelloCR, abrí este enlace:</p>
                <p><a href="%s">Verificar mi correo</a></p>
                <p>El enlace vence en %s. Si no creaste esta cuenta, ignorá este correo.</p>
                """.formatted(escapar(usuario.getNombreVisible()), enlace, vence);
        return new CorreoSaliente(usuario.getCorreo(), "Verificá tu correo en HelloCR", texto, html);
    }

    public CorreoSaliente recuperacion(Usuario usuario, String token) {
        String enlace = urlPublica + "/restablecer?token=" + token;
        String vence = describir(propiedades.duracionRecuperacion());
        String texto = """
                ¡Hola, %s!

                Recibimos un pedido para cambiar la contraseña de tu cuenta @%s en HelloCR. Para elegir una nueva, abrí este enlace:
                %s

                El enlace vence en %s. Si no fuiste vos, ignorá este correo: tu contraseña sigue igual.
                """.formatted(usuario.getNombreVisible(), usuario.getNombreUsuario(), enlace, vence);
        String html = """
                <p>¡Hola, %s!</p>
                <p>Recibimos un pedido para cambiar la contraseña de tu cuenta @%s en HelloCR. Para elegir una nueva, abrí este enlace:</p>
                <p><a href="%s">Elegir una contraseña nueva</a></p>
                <p>El enlace vence en %s. Si no fuiste vos, ignorá este correo: tu contraseña sigue igual.</p>
                """.formatted(escapar(usuario.getNombreVisible()), usuario.getNombreUsuario(), enlace, vence);
        return new CorreoSaliente(usuario.getCorreo(), "Restablecé tu contraseña de HelloCR", texto, html);
    }

    /** "24 horas", "1 hora", "90 minutos", "1 minuto". */
    static String describir(Duration duracion) {
        long horas = duracion.toHours();
        if (horas > 0 && duracion.toMinutesPart() == 0) {
            return horas == 1 ? "1 hora" : horas + " horas";
        }
        long minutos = duracion.toMinutes();
        return minutos == 1 ? "1 minuto" : minutos + " minutos";
    }

    /** El nombre visible lo escribe la persona: se escapa para que no inyecte HTML en el correo. */
    private static String escapar(String texto) {
        return HtmlUtils.htmlEscape(texto, "UTF-8");
    }
}
