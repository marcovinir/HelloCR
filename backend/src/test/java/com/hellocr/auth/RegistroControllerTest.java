package com.hellocr.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hellocr.correo.CorreoSaliente;
import com.hellocr.soporte.BuzonPrueba;
import com.hellocr.soporte.PruebaIntegracion;
import com.hellocr.usuarios.Usuario;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

class RegistroControllerTest extends PruebaIntegracion {

    @Test
    void registroCreaLaCuentaSinSesionYEnviaElCorreoDeVerificacion() throws Exception {
        registrar("ana@correo.cr", "ana", "Ana Mora", CLAVE)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.correo").value("ana@correo.cr"))
                .andExpect(jsonPath("$.accessToken").doesNotExist())
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));

        CorreoSaliente correo = buzon.ultimoPara("ana@correo.cr");
        assertThat(correo.asunto()).isEqualTo("Verificá tu correo en HelloCR");
        assertThat(correo.texto()).contains("¡Hola, Ana Mora!").contains("http://localhost:5173/verificar?token=");
        login("ana", CLAVE)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("CORREO_NO_VERIFICADO"));
    }

    @Test
    void verificarConElEnlaceDelCorreoAbreSesion() throws Exception {
        registrar("ana@correo.cr", "ana", "Ana Mora", CLAVE).andExpect(status().isCreated());

        verificar(tokenDelCorreo("ana@correo.cr"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.usuario.nombreUsuario").value("ana"))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("refresh_token=")));
        iniciarSesion("ana");
    }

    @Test
    void verificarDosVecesFallaLaSegundaPeroLaCuentaQuedaVerificada() throws Exception {
        registrar("ana@correo.cr", "ana", "Ana Mora", CLAVE).andExpect(status().isCreated());
        String token = tokenDelCorreo("ana@correo.cr");

        verificar(token).andExpect(status().isOk());
        verificar(token)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.codigo").value("TOKEN_INVALIDO"))
                .andExpect(jsonPath("$.detail").value("El enlace no es válido o ya venció. Pedí uno nuevo."));
        iniciarSesion("ana");
    }

    @Test
    void unEnlaceVencidoNoSirve() throws Exception {
        registrar("ana@correo.cr", "ana", "Ana Mora", CLAVE).andExpect(status().isCreated());
        String token = tokenDelCorreo("ana@correo.cr");
        reloj.avanzar(Duration.ofHours(24));

        verificar(token).andExpect(status().isUnprocessableContent());
    }

    @Test
    void normalizaElCorreoYElNombreDeUsuario() throws Exception {
        registrar("  Ana@Correo.CR ", "@Ana", "  Ana  ", CLAVE)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.correo").value("ana@correo.cr"));

        assertThat(jdbc.queryForMap("SELECT correo, nombre_usuario, nombre_visible FROM usuarios"))
                .containsEntry("correo", "ana@correo.cr")
                .containsEntry("nombre_usuario", "ana")
                .containsEntry("nombre_visible", "Ana");
    }

    @Test
    void datosInvalidosDevuelvenLosErroresPorCampo() throws Exception {
        registrar("no-es-correo", "1a", "", "corta")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("VALIDACION"))
                .andExpect(jsonPath("$.errores.correo").value("El correo no es válido."))
                .andExpect(jsonPath("$.errores.nombreUsuario").value(Usuario.MENSAJE_FORMATO_NOMBRE_USUARIO))
                .andExpect(jsonPath("$.errores.nombreVisible").value("El nombre es obligatorio."))
                .andExpect(jsonPath("$.errores.contrasena").value("La contraseña debe tener entre 8 y 64 caracteres."));
    }

    @Test
    void losNombresReservadosNoSeAceptan() throws Exception {
        registrar("ana@correo.cr", "Admin", "Ana", CLAVE)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.codigo").value("NOMBRE_USUARIO_RESERVADO"));
    }

    @Test
    void unCorreoVerificadoOUnNombreEnUsoDan409() throws Exception {
        crearUsuario("ana");

        registrar("ana@correo.cr", "otra", "Otra", CLAVE)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("CORREO_EN_USO"));
        registrar("otra@correo.cr", "ana", "Otra", CLAVE)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("NOMBRE_USUARIO_EN_USO"));
    }

    @Test
    void registrarseDeNuevoConUnCorreoSinVerificarReemplazaLaCuentaVieja() throws Exception {
        registrar("ana@correo.cr", "ana", "Ana", CLAVE).andExpect(status().isCreated());
        String tokenViejo = tokenDelCorreo("ana@correo.cr");
        reloj.avanzar(Duration.ofMinutes(1));

        registrar("ana@correo.cr", "ana_mora", "Ana Mora", "otra-clave-123").andExpect(status().isCreated());

        verificar(tokenViejo).andExpect(status().isUnprocessableContent());
        verificar(tokenDelCorreo("ana@correo.cr"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usuario.nombreUsuario").value("ana_mora"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM usuarios", Integer.class)).isEqualTo(1);
    }

    @Test
    void registrarseDeNuevoAntesDeUnMinutoNoMandaOtroCorreo() throws Exception {
        registrar("ana@correo.cr", "ana", "Ana", CLAVE).andExpect(status().isCreated());

        registrar("ana@correo.cr", "ana", "Ana", CLAVE)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.codigo").value("DEMASIADOS_INTENTOS"))
                .andExpect(jsonPath("$.detail").value("Ya te mandamos un correo hace poco. Probá de nuevo en 1 minuto."))
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "60"));
        assertThat(buzon.para("ana@correo.cr")).hasSize(1);

        reloj.avanzar(Duration.ofMinutes(1));
        registrar("ana@correo.cr", "ana", "Ana", CLAVE).andExpect(status().isCreated());
        assertThat(buzon.para("ana@correo.cr")).hasSize(2);
    }

    @Test
    void unaContrasenaDeMasDe72BytesSeRechazaSinErrorInterno() throws Exception {
        registrar("ana@correo.cr", "ana", "Ana", "ñ".repeat(40))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("VALIDACION"))
                .andExpect(jsonPath("$.errores.contrasena").value("La contraseña es demasiado larga."));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM usuarios", Integer.class)).isZero();
    }

    @Test
    void registrosSimultaneosDejanUnaSolaCuenta() throws Exception {
        List<Integer> estados = enParalelo(6,
                () -> registrar("ana@correo.cr", "ana", "Ana", CLAVE).andReturn().getResponse().getStatus());

        assertThat(estados).allMatch(estado -> estado == 201 || estado == 409 || estado == 429).contains(201);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM usuarios", Integer.class)).isEqualTo(1);
    }

    @Test
    void reenviarMandaUnEnlaceNuevoEInvalidaElAnterior() throws Exception {
        registrar("ana@correo.cr", "ana", "Ana", CLAVE).andExpect(status().isCreated());
        String viejo = tokenDelCorreo("ana@correo.cr");
        reloj.avanzar(Duration.ofMinutes(1));

        reenviar(" ANA@correo.cr").andExpect(status().isNoContent());

        assertThat(buzon.para("ana@correo.cr")).hasSize(2);
        verificar(viejo).andExpect(status().isUnprocessableContent());
        verificar(tokenDelCorreo("ana@correo.cr")).andExpect(status().isOk());
    }

    @Test
    void reenviarRespetaLaEsperaYNoRevelaNada() throws Exception {
        registrar("ana@correo.cr", "ana", "Ana", CLAVE).andExpect(status().isCreated());
        crearUsuario("luis");

        reenviar("ana@correo.cr").andExpect(status().isNoContent());
        reenviar("luis@correo.cr").andExpect(status().isNoContent());
        reenviar("nadie@correo.cr").andExpect(status().isNoContent());

        assertThat(buzon.todos()).hasSize(1);
    }

    private String tokenDelCorreo(String correo) {
        return BuzonPrueba.tokenDe(buzon.ultimoPara(correo));
    }

    private ResultActions registrar(String correo, String nombreUsuario, String nombreVisible, String contrasena)
            throws Exception {
        return mvc.perform(post("/api/auth/registro").contentType(MediaType.APPLICATION_JSON).content("""
                {"correo": "%s", "nombreUsuario": "%s", "nombreVisible": "%s", "contrasena": "%s"}
                """.formatted(correo, nombreUsuario, nombreVisible, contrasena)));
    }

    private ResultActions verificar(String token) throws Exception {
        return mvc.perform(post("/api/auth/verificar").contentType(MediaType.APPLICATION_JSON).content("""
                {"token": "%s"}
                """.formatted(token)));
    }

    private ResultActions reenviar(String correo) throws Exception {
        return mvc.perform(post("/api/auth/reenviar-verificacion").contentType(MediaType.APPLICATION_JSON).content("""
                {"correo": "%s"}
                """.formatted(correo)));
    }

    private ResultActions login(String identificador, String contrasena) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
                {"identificador": "%s", "contrasena": "%s"}
                """.formatted(identificador, contrasena)));
    }
}
