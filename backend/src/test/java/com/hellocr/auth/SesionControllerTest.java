package com.hellocr.auth;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hellocr.soporte.PruebaIntegracion;
import com.hellocr.soporte.SesionPrueba;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

class SesionControllerTest extends PruebaIntegracion {

    @Test
    void loginConElNombreDeUsuarioAbreSesion() throws Exception {
        crearUsuario("ana");

        login("ana", CLAVE)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.usuario.nombreUsuario").value("ana"))
                .andExpect(jsonPath("$.usuario.correo").value("ana@correo.cr"))
                .andExpect(jsonPath("$.usuario.nombreVisible").value("Usuario ana"))
                .andExpect(jsonPath("$.usuario.codigoInvitacion").isNotEmpty())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, allOf(
                        containsString("refresh_token="), containsString("HttpOnly"),
                        containsString("SameSite=Strict"), containsString("Path=/api/auth"),
                        containsString("Max-Age=2592000"))));
    }

    @Test
    void loginAceptaCorreoOUsuarioConMayusculasEspaciosYArroba() throws Exception {
        crearUsuario("ana");

        for (String identificador : List.of("  ANA@Correo.cr ", "@Ana", " ana ")) {
            login(identificador, CLAVE).andExpect(status().isOk());
        }
    }

    @Test
    void loginFallidoNoRevelaSiLaCuentaExiste() throws Exception {
        crearUsuario("ana");

        login("ana", "otra-clave")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.codigo").value("CREDENCIALES_INVALIDAS"))
                .andExpect(jsonPath("$.detail").value("El usuario o la contraseña no son correctos."));
        login("nadie", CLAVE)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("El usuario o la contraseña no son correctos."));
    }

    @Test
    void unaCuentaSinVerificarNoEntraYSoloLoSabeQuienTieneLaClave() throws Exception {
        crearUsuarioSinVerificar("ana");

        login("ana", CLAVE)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("CORREO_NO_VERIFICADO"))
                .andExpect(jsonPath("$.detail").value("Todavía no verificaste tu correo. Revisá tu bandeja de entrada."));
        login("ana", "otra-clave")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.codigo").value("CREDENCIALES_INVALIDAS"));
    }

    @Test
    void cincoFallosBloqueanQuinceMinutosAunConLaClaveCorrecta() throws Exception {
        crearUsuario("ana");
        for (int i = 0; i < 5; i++) {
            login("ana", "mala-clave").andExpect(status().isUnauthorized());
        }

        login("ana", CLAVE)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.codigo").value("DEMASIADOS_INTENTOS"))
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "900"));
        loginDesde("10.0.0.2", "ana", CLAVE).andExpect(status().isOk());

        reloj.avanzar(Duration.ofMinutes(15));
        login("ana", CLAVE).andExpect(status().isOk());
    }

    @Test
    void unaContrasenaDeMasDe72BytesNoProvocaErrorInterno() throws Exception {
        crearUsuario("ana");

        login("ana", "ñ".repeat(40)).andExpect(status().isUnauthorized());
    }

    @Test
    void camposVaciosDevuelvenLosErroresPorCampo() throws Exception {
        login("", "")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("VALIDACION"))
                .andExpect(jsonPath("$.errores.identificador").value("Escribí tu correo o tu nombre de usuario."))
                .andExpect(jsonPath("$.errores.contrasena").value("Escribí tu contraseña."));
    }

    @Test
    void unJsonMalFormadoEs400() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"identificador\": "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("VALIDACION"));
    }

    @Test
    void refreshRotaLaCookieYEntregaUnAccessTokenNuevo() throws Exception {
        SesionPrueba inicial = sesionDe("ana");
        reloj.avanzar(Duration.ofMinutes(20));

        SesionPrueba renovada = SesionPrueba.de(mvc.perform(post("/api/auth/refresh").cookie(inicial.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usuario.nombreUsuario").value("ana"))
                .andReturn());

        // El token viejo venció (401); con el nuevo la solicitud llega al controlador (404: la ruta no existe).
        mvc.perform(get("/api/no-existe").with(con(inicial))).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/no-existe").with(con(renovada))).andExpect(status().isNotFound());
    }

    @Test
    void refreshFuncionaAunqueElClienteMandeUnBearerVencido() throws Exception {
        SesionPrueba sesion = sesionDe("ana");
        reloj.avanzar(Duration.ofHours(1));

        mvc.perform(post("/api/auth/refresh").cookie(sesion.cookie()).with(con(sesion)))
                .andExpect(status().isOk());
    }

    @Test
    void reusarUnaCookieViejaCierraTodasLasSesiones() throws Exception {
        SesionPrueba vieja = sesionDe("ana");
        SesionPrueba nueva = SesionPrueba.de(mvc.perform(post("/api/auth/refresh").cookie(vieja.cookie())).andReturn());
        reloj.avanzar(Duration.ofMinutes(1));

        mvc.perform(post("/api/auth/refresh").cookie(vieja.cookie()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.codigo").value("NO_AUTENTICADO"));
        mvc.perform(post("/api/auth/refresh").cookie(nueva.cookie()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logoutRevocaElRefreshYBorraLaCookie() throws Exception {
        SesionPrueba sesion = sesionDe("ana");

        mvc.perform(post("/api/auth/logout").cookie(sesion.cookie()))
                .andExpect(status().isNoContent())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, allOf(
                        containsString("refresh_token=;"), containsString("Max-Age=0"))));
        mvc.perform(post("/api/auth/refresh").cookie(sesion.cookie()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void refreshSinCookieEs401() throws Exception {
        mvc.perform(post("/api/auth/refresh"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.codigo").value("NO_AUTENTICADO"));
    }

    private ResultActions login(String identificador, String contrasena) throws Exception {
        return loginDesde("127.0.0.1", identificador, contrasena);
    }

    private ResultActions loginDesde(String ip, String identificador, String contrasena) throws Exception {
        return mvc.perform(post("/api/auth/login")
                .with(solicitud -> {
                    solicitud.setRemoteAddr(ip);
                    return solicitud;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"identificador": "%s", "contrasena": "%s"}
                        """.formatted(identificador, contrasena)));
    }
}
