package com.hellocr.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hellocr.correo.CorreoSaliente;
import com.hellocr.soporte.BuzonPrueba;
import com.hellocr.soporte.PruebaIntegracion;
import com.hellocr.soporte.SesionPrueba;
import com.hellocr.usuarios.Usuario;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.ResultActions;

@RecordApplicationEvents
class RecuperacionControllerTest extends PruebaIntegracion {

    private static final String NUEVA = "nueva-clave-123";

    @Autowired
    private ApplicationEvents eventos;
    @Autowired
    private TokenCorreoService tokensCorreo;

    @Test
    void recuperarEnviaUnEnlaceParaRestablecer() throws Exception {
        crearUsuario("ana");

        recuperar(" ANA@correo.cr ").andExpect(status().isNoContent());

        CorreoSaliente correo = buzon.ultimoPara("ana@correo.cr");
        assertThat(correo.asunto()).isEqualTo("Restablecé tu contraseña de HelloCR");
        assertThat(correo.texto()).contains("@ana").contains("http://localhost:5173/restablecer?token=");
    }

    @Test
    void recuperarNoRevelaSiLaCuentaExisteYRespetaLaEspera() throws Exception {
        recuperar("nadie@correo.cr").andExpect(status().isNoContent());
        assertThat(buzon.todos()).isEmpty();

        crearUsuario("ana");
        recuperar("ana@correo.cr").andExpect(status().isNoContent());
        recuperar("ana@correo.cr").andExpect(status().isNoContent());
        assertThat(buzon.todos()).hasSize(1);

        reloj.avanzar(Duration.ofMinutes(1));
        recuperar("ana@correo.cr").andExpect(status().isNoContent());
        assertThat(buzon.todos()).hasSize(2);
    }

    @Test
    void restablecerCambiaLaContrasenaYCierraLasSesiones() throws Exception {
        SesionPrueba sesion = sesionDe("ana");
        recuperar("ana@correo.cr");

        restablecer(tokenDelCorreo(), NUEVA).andExpect(status().isNoContent());

        login("ana", CLAVE).andExpect(status().isUnauthorized());
        login("ana", NUEVA).andExpect(status().isOk());
        mvc.perform(post("/api/auth/refresh").cookie(sesion.cookie())).andExpect(status().isUnauthorized());
        assertThat(eventos.stream(SesionesRevocadas.class))
                .containsExactly(new SesionesRevocadas(UUID.fromString(sesion.usuarioId())));
    }

    @Test
    void laCookieViejaDeOtroDispositivoNoCierraLaSesionNuevaDespuesDeRestablecer() throws Exception {
        SesionPrueba pc = sesionDe("ana");
        recuperar("ana@correo.cr");
        restablecer(tokenDelCorreo(), NUEVA).andExpect(status().isNoContent());
        SesionPrueba celular = SesionPrueba.de(login("ana", NUEVA).andExpect(status().isOk()).andReturn());
        reloj.avanzar(Duration.ofSeconds(31));

        mvc.perform(post("/api/auth/refresh").cookie(pc.cookie())).andExpect(status().isUnauthorized());

        mvc.perform(post("/api/auth/refresh").cookie(celular.cookie())).andExpect(status().isOk());
    }

    @Test
    void restablecerVerificaUnaCuentaSinVerificar() throws Exception {
        crearUsuarioSinVerificar("ana");
        recuperar("ana@correo.cr");

        restablecer(tokenDelCorreo(), NUEVA).andExpect(status().isNoContent());

        login("ana", NUEVA).andExpect(status().isOk());
    }

    @Test
    void elEnlaceSirveUnaSolaVezYVenceEnUnaHora() throws Exception {
        crearUsuario("ana");
        recuperar("ana@correo.cr");
        String token = tokenDelCorreo();

        restablecer(token, NUEVA).andExpect(status().isNoContent());
        restablecer(token, "otra-clave-456")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.codigo").value("TOKEN_INVALIDO"));

        reloj.avanzar(Duration.ofMinutes(1));
        recuperar("ana@correo.cr");
        String segundo = tokenDelCorreo();
        reloj.avanzar(Duration.ofHours(1));
        restablecer(segundo, "otra-clave-456").andExpect(status().isUnprocessableContent());
    }

    @Test
    void unEnlaceDeVerificacionNoSirveParaRestablecer() throws Exception {
        Usuario ana = crearUsuarioSinVerificar("ana");
        String deVerificacion = tokensCorreo.emitir(ana, PropositoToken.VERIFICACION);

        restablecer(deVerificacion, NUEVA).andExpect(status().isUnprocessableContent());
    }

    @Test
    void unaContrasenaNuevaInvalidaNoGastaElEnlace() throws Exception {
        crearUsuario("ana");
        recuperar("ana@correo.cr");
        String token = tokenDelCorreo();

        restablecer(token, "corta")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores.contrasenaNueva").value("La contraseña debe tener entre 8 y 64 caracteres."));
        restablecer(token, "ñ".repeat(40))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores.contrasenaNueva").value("La contraseña es demasiado larga."));
        restablecer(token, NUEVA).andExpect(status().isNoContent());
    }

    private String tokenDelCorreo() {
        return BuzonPrueba.tokenDe(buzon.ultimoPara("ana@correo.cr"));
    }

    private ResultActions recuperar(String correo) throws Exception {
        return mvc.perform(post("/api/auth/recuperar").contentType(MediaType.APPLICATION_JSON).content("""
                {"correo": "%s"}
                """.formatted(correo)));
    }

    private ResultActions restablecer(String token, String contrasenaNueva) throws Exception {
        return mvc.perform(post("/api/auth/restablecer").contentType(MediaType.APPLICATION_JSON).content("""
                {"token": "%s", "contrasenaNueva": "%s"}
                """.formatted(token, contrasenaNueva)));
    }

    private ResultActions login(String identificador, String contrasena) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
                {"identificador": "%s", "contrasena": "%s"}
                """.formatted(identificador, contrasena)));
    }
}
