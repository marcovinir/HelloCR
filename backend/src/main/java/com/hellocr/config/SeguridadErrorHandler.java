package com.hellocr.config;

import com.hellocr.comun.CodigoError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Respuestas 401/403 generadas por los filtros de seguridad, con el mismo formato que ApiErrorHandler. */
@Component
public class SeguridadErrorHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final JsonMapper json;

    public SeguridadErrorHandler(JsonMapper json) {
        this.json = json;
    }

    @Override
    public void commence(HttpServletRequest solicitud, HttpServletResponse respuesta,
            AuthenticationException error) throws IOException {
        escribir(respuesta, CodigoError.NO_AUTENTICADO, "Tenés que iniciar sesión.");
    }

    @Override
    public void handle(HttpServletRequest solicitud, HttpServletResponse respuesta,
            AccessDeniedException error) throws IOException {
        escribir(respuesta, CodigoError.SIN_PERMISO, "No tenés permiso para esta acción.");
    }

    private void escribir(HttpServletResponse respuesta, CodigoError codigo, String detalle) throws IOException {
        Map<String, Object> cuerpo = new LinkedHashMap<>();
        cuerpo.put("type", "about:blank");
        cuerpo.put("title", codigo.estado().getReasonPhrase());
        cuerpo.put("status", codigo.estado().value());
        cuerpo.put("detail", detalle);
        cuerpo.put("codigo", codigo.name());
        respuesta.setStatus(codigo.estado().value());
        respuesta.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        respuesta.setCharacterEncoding(StandardCharsets.UTF_8);
        json.writeValue(respuesta.getOutputStream(), cuerpo);
    }
}
