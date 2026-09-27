package com.hellocr.usuarios;

import static org.assertj.core.api.Assertions.assertThat;

import com.hellocr.soporte.PruebaIntegracion;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

class UsuarioRepositoryTest extends PruebaIntegracion {

    @Autowired
    private UsuarioRepository usuarios;

    @Test
    void alGuardarGeneraUnUuidVersion7() {
        Usuario ana = crearUsuario("ana");

        assertThat(ana.getId().version()).isEqualTo(7);
    }

    @Test
    void buscaPorCorreoNombreYCodigo() {
        Usuario ana = crearUsuario("ana");

        assertThat(usuarios.findByCorreo("ana@correo.cr")).isPresent();
        assertThat(usuarios.findByNombreUsuario("ana")).isPresent();
        assertThat(usuarios.findByCodigoInvitacion(ana.getCodigoInvitacion())).isPresent();
    }

    @Test
    void lasBusquedasDePerfilesSoloVenCuentasVerificadas() {
        Usuario ana = crearUsuario("ana");
        Usuario luis = crearUsuarioSinVerificar("luis");

        assertThat(usuarios.verificadoPorId(ana.getId())).isPresent();
        assertThat(usuarios.verificadoPorId(luis.getId())).isEmpty();
        assertThat(usuarios.verificadoPorNombreUsuario("luis")).isEmpty();
        assertThat(usuarios.verificadoPorCodigo(luis.getCodigoInvitacion())).isEmpty();
    }

    @Test
    @Transactional
    void borrarSinVerificarPorCorreoNoTocaCuentasVerificadas() {
        crearUsuario("ana");
        crearUsuarioSinVerificar("luis");

        assertThat(usuarios.borrarSinVerificarPorCorreo("ana@correo.cr")).isZero();
        assertThat(usuarios.borrarSinVerificarPorCorreo("luis@correo.cr")).isEqualTo(1);
    }

    @Test
    @Transactional
    void borraLasCuentasSinVerificarCreadasAntesDeUnaFecha() {
        crearUsuarioSinVerificar("vieja");
        reloj.avanzar(Duration.ofDays(3));
        crearUsuarioSinVerificar("nueva");
        crearUsuario("ana");

        assertThat(usuarios.borrarSinVerificarCreadosAntesDe(reloj.instant().minus(Duration.ofDays(1)))).isEqualTo(1);
    }
}
