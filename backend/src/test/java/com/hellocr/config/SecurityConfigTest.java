package com.hellocr.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hellocr.auth.JwtService;
import com.hellocr.soporte.PruebaIntegracion;
import com.hellocr.usuarios.Usuario;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

class SecurityConfigTest extends PruebaIntegracion {

    @Autowired
    private JwtService jwtService;
    @Autowired
    private JwtDecoder jwtDecoder;

    @Test
    void elTokenLlevaElIdYElNombreDeUsuarioYVenceEn15Minutos() {
        Usuario ana = crearUsuario("ana");

        Jwt jwt = jwtDecoder.decode(jwtService.emitir(ana));

        assertThat(jwt.getSubject()).isEqualTo(ana.getId().toString());
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("hellocr");
        assertThat(jwt.getClaimAsString("nombreUsuario")).isEqualTo("ana");
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void sinTokenUnaRutaProtegidaResponde401ConFormatoDeError() throws Exception {
        mvc.perform(get("/api/usuarios/yo"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.codigo").value("NO_AUTENTICADO"))
                .andExpect(jsonPath("$.detail").value("Tenés que iniciar sesión."));
    }

    @Test
    void conTokenValidoLaSolicitudLlegaAlControlador() throws Exception {
        String token = jwtService.emitir(crearUsuario("ana"));

        mvc.perform(get("/api/no-existe").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value("NO_ENCONTRADO"));
    }

    @Test
    void unTokenVencidoResponde401() throws Exception {
        String token = jwtService.emitir(crearUsuario("ana"));
        reloj.avanzar(Duration.ofMinutes(15).plusSeconds(1));

        mvc.perform(get("/api/no-existe").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.codigo").value("NO_AUTENTICADO"));
    }

    @Test
    void unTokenAlteradoResponde401() throws Exception {
        String token = jwtService.emitir(crearUsuario("ana"));
        String alterado = token.substring(0, token.length() - 4) + "AAAA";

        mvc.perform(get("/api/no-existe").header(HttpHeaders.AUTHORIZATION, "Bearer " + alterado))
                .andExpect(status().isUnauthorized());
    }
}
