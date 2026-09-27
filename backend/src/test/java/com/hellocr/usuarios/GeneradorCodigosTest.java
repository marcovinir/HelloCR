package com.hellocr.usuarios;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

class GeneradorCodigosTest {

    @Test
    void generaOchoCaracteresSinCaracteresAmbiguos() {
        GeneradorCodigos generador = new GeneradorCodigos(mock(UsuarioRepository.class));

        for (int i = 0; i < 1000; i++) {
            assertThat(generador.codigoInvitacion()).matches("[2-9A-HJKMNP-Za-kmnp-z]{8}");
        }
    }

    @Test
    void siElCodigoYaExisteGeneraOtro() {
        UsuarioRepository usuarios = mock(UsuarioRepository.class);
        when(usuarios.existsByCodigoInvitacion(anyString())).thenReturn(true, false);

        new GeneradorCodigos(usuarios).codigoInvitacion();

        verify(usuarios, times(2)).existsByCodigoInvitacion(anyString());
    }
}
