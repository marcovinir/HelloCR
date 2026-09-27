package com.hellocr.correo;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/** Para producción: envía por SMTP (Gmail) con la configuración spring.mail.*. */
@Component
@ConditionalOnProperty(name = "app.correo.modo", havingValue = "smtp")
public class EnviadorCorreoSmtp implements EnviadorCorreo {

    private final JavaMailSender mailSender;
    private final CorreoProperties propiedades;

    public EnviadorCorreoSmtp(JavaMailSender mailSender, CorreoProperties propiedades) {
        this.mailSender = mailSender;
        this.propiedades = propiedades;
    }

    @Override
    public void enviar(CorreoSaliente correo) {
        MimeMessage mensaje = mailSender.createMimeMessage();
        try {
            MimeMessageHelper ayudante = new MimeMessageHelper(mensaje, true, StandardCharsets.UTF_8.name());
            ayudante.setFrom(propiedades.remitente());
            ayudante.setTo(correo.destinatario());
            ayudante.setSubject(correo.asunto());
            ayudante.setText(correo.texto(), correo.html());
        } catch (MessagingException error) {
            throw new IllegalStateException("No se pudo armar el correo para " + correo.destinatario(), error);
        }
        mailSender.send(mensaje);
    }
}
