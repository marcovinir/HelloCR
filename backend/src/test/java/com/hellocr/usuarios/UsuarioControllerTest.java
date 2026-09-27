package com.hellocr.usuarios;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hellocr.soporte.PruebaIntegracion;
import com.hellocr.soporte.SesionPrueba;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

class UsuarioControllerTest extends PruebaIntegracion {

    @Test
    void yoDevuelveElPerfilPropio() throws Exception {
        SesionPrueba ana = sesionDe("ana");

        mvc.perform(get("/api/usuarios/yo").with(con(ana)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ana.usuarioId()))
                .andExpect(jsonPath("$.correo").value("ana@correo.cr"))
                .andExpect(jsonPath("$.nombreUsuario").value("ana"))
                .andExpect(jsonPath("$.nombreVisible").value("Usuario ana"))
                .andExpect(jsonPath("$.codigoInvitacion").value(codigoDe("ana")));
    }

    @Test
    void actualizaElNombreYLaInfo() throws Exception {
        SesionPrueba ana = sesionDe("ana");

        actualizar(ana, """
                {"nombreVisible": "  Ana Mora ", "info": "Pura vida"}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nombreVisible").value("Ana Mora"))
                .andExpect(jsonPath("$.info").value("Pura vida"));
        actualizar(ana, """
                {"info": ""}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nombreVisible").value("Ana Mora"))
                .andExpect(jsonPath("$.info").value(nullValue()));
    }

    @Test
    void unNombreVisibleVacioOUnaInfoLargaSonInvalidos() throws Exception {
        SesionPrueba ana = sesionDe("ana");

        actualizar(ana, """
                {"nombreVisible": "   ", "info": "%s"}
                """.formatted("a".repeat(141)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores.nombreVisible").value("El nombre debe tener entre 1 y 50 caracteres."))
                .andExpect(jsonPath("$.errores.info").value("La info puede tener hasta 140 caracteres."));
    }

    @Test
    void cambiaElNombreDeUsuarioNormalizado() throws Exception {
        SesionPrueba ana = sesionDe("ana");

        actualizar(ana, """
                {"nombreUsuario": " @Ana_Mora "}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nombreUsuario").value("ana_mora"));
        iniciarSesion("ana_mora");
    }

    @Test
    void noSePuedeTomarUnNombreAjenoNiReservado() throws Exception {
        SesionPrueba ana = sesionDe("ana");
        crearUsuario("luis");

        actualizar(ana, "{\"nombreUsuario\": \"luis\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("NOMBRE_USUARIO_EN_USO"));
        actualizar(ana, "{\"nombreUsuario\": \"soporte\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.codigo").value("NOMBRE_USUARIO_RESERVADO"));
        actualizar(ana, "{\"nombreUsuario\": \"x\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores.nombreUsuario").value(Usuario.MENSAJE_FORMATO_NOMBRE_USUARIO));
        actualizar(ana, "{\"nombreUsuario\": \"@ANA\"}").andExpect(status().isOk());
    }

    @Test
    void regenerarElCodigoInvalidaElLinkAnterior() throws Exception {
        SesionPrueba ana = sesionDe("ana");
        SesionPrueba luis = sesionDe("luis");
        String viejo = codigoDe("ana");

        String respuesta = mvc.perform(post("/api/usuarios/yo/codigo-invitacion").with(con(ana)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.codigoInvitacion").value(not(viejo)))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String nuevo = JsonPath.read(respuesta, "$.codigoInvitacion");

        assertThat(nuevo).matches("[2-9A-HJKMNP-Za-kmnp-z]{8}");
        mvc.perform(get("/api/invitaciones/{codigo}", viejo).with(con(luis)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value("NO_ENCONTRADO"));
        mvc.perform(get("/api/invitaciones/{codigo}", nuevo).with(con(luis)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nombreUsuario").value("ana"));
    }

    @Test
    void buscarSoloEncuentraCoincidenciasExactasDeCuentasVerificadas() throws Exception {
        SesionPrueba ana = sesionDe("ana");
        crearUsuario("luis");
        crearUsuarioSinVerificar("sofia");

        buscar(ana, " @Luis ")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.nombreUsuario").value("luis"))
                .andExpect(jsonPath("$.nombreVisible").value("Usuario luis"))
                .andExpect(jsonPath("$.correo").doesNotExist())
                .andExpect(jsonPath("$.codigoInvitacion").doesNotExist());
        buscar(ana, "lu")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("No encontramos a esa persona."));
        buscar(ana, "sofia").andExpect(status().isNotFound());
    }

    @Test
    void elPerfilPublicoPorIdSoloMuestraCuentasVerificadas() throws Exception {
        SesionPrueba ana = sesionDe("ana");
        Usuario luis = crearUsuario("luis");
        Usuario sofia = crearUsuarioSinVerificar("sofia");

        mvc.perform(get("/api/usuarios/{id}", luis.getId()).with(con(ana)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nombreUsuario").value("luis"));
        mvc.perform(get("/api/usuarios/{id}", sofia.getId()).with(con(ana)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/usuarios/no-es-un-uuid").with(con(ana)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("VALIDACION"));
    }

    @Test
    void lasInvitacionesRequierenSesion() throws Exception {
        crearUsuario("ana");

        mvc.perform(get("/api/invitaciones/{codigo}", codigoDe("ana")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void elTokenDeUnaCuentaBorradaYaNoSirve() throws Exception {
        SesionPrueba ana = sesionDe("ana");
        jdbc.update("DELETE FROM usuarios");

        mvc.perform(get("/api/usuarios/yo").with(con(ana)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.codigo").value("NO_AUTENTICADO"));
    }

    private String codigoDe(String nombreUsuario) {
        return jdbc.queryForObject("SELECT codigo_invitacion FROM usuarios WHERE nombre_usuario = ?", String.class,
                nombreUsuario);
    }

    private ResultActions actualizar(SesionPrueba sesion, String json) throws Exception {
        return mvc.perform(patch("/api/usuarios/yo").with(con(sesion))
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions buscar(SesionPrueba sesion, String nombreUsuario) throws Exception {
        return mvc.perform(get("/api/usuarios/buscar").param("nombreUsuario", nombreUsuario).with(con(sesion)));
    }
}
