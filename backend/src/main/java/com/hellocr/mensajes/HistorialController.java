package com.hellocr.mensajes;

import com.hellocr.comun.UsuarioAutenticado;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Los mensajes se leen por REST y se envían solo por STOMP (spec 9.2). */
@RestController
@RequestMapping("/api/conversaciones/{id}/mensajes")
public class HistorialController {

    private final HistorialService historial;

    public HistorialController(HistorialService historial) {
        this.historial = historial;
    }

    @GetMapping
    public PaginaMensajes pagina(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @RequestParam(required = false) Long antesDe, @RequestParam(required = false) Long despuesDe,
            @RequestParam(required = false) Integer limite) {
        return historial.pagina(UsuarioAutenticado.id(jwt), id, antesDe, despuesDe, limite);
    }
}
