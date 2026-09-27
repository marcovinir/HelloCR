package com.hellocr.correo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.javamail.JavaMailSender;

class EnviadorCorreoSmtpTest {

    @Test
    void armaUnCorreoConRemitenteDestinatarioAsuntoYAmbasVersiones() throws Exception {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        when(mailSender.createMimeMessage()).thenReturn(new MimeMessage((Session) null));
        CorreoProperties propiedades = new CorreoProperties(CorreoProperties.Modo.SMTP,
                "HelloCR <no-responder@hellocr.local>", true, Duration.ofHours(24), Duration.ofHours(1),
                Duration.ofMinutes(1));

        new EnviadorCorreoSmtp(mailSender, propiedades).enviar(
                new CorreoSaliente("ana@correo.cr", "Verificá tu correo en HelloCR", "texto plano", "<p>html</p>"));

        ArgumentCaptor<MimeMessage> enviado = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(enviado.capture());
        MimeMessage mensaje = enviado.getValue();
        InternetAddress remitente = (InternetAddress) mensaje.getFrom()[0];
        assertThat(remitente.getAddress()).isEqualTo("no-responder@hellocr.local");
        assertThat(remitente.getPersonal()).isEqualTo("HelloCR");
        assertThat(mensaje.getAllRecipients()[0].toString()).isEqualTo("ana@correo.cr");
        assertThat(mensaje.getSubject()).isEqualTo("Verificá tu correo en HelloCR");
        ByteArrayOutputStream crudo = new ByteArrayOutputStream();
        mensaje.writeTo(crudo);
        assertThat(crudo.toString(StandardCharsets.UTF_8)).contains("texto plano").contains("<p>html</p>");
    }
}
