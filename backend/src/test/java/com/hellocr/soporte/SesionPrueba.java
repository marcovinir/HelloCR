package com.hellocr.soporte;

import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import org.springframework.test.web.servlet.MvcResult;

/** Lo que un test necesita de una sesión abierta con /login, /refresh o /verificar. */
public record SesionPrueba(String accessToken, String refreshToken, String usuarioId) {

    public static SesionPrueba de(MvcResult resultado) throws Exception {
        String json = resultado.getResponse().getContentAsString(StandardCharsets.UTF_8);
        Cookie cookie = resultado.getResponse().getCookie("refresh_token");
        return new SesionPrueba(JsonPath.read(json, "$.accessToken"),
                cookie == null ? null : cookie.getValue(), JsonPath.read(json, "$.usuario.id"));
    }

    public Cookie cookie() {
        return new Cookie("refresh_token", refreshToken);
    }
}
