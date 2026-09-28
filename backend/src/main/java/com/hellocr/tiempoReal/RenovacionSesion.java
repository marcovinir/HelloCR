package com.hellocr.tiempoReal;

import com.hellocr.comun.CodigoError;
import com.hellocr.comun.ErrorNegocio;
import com.hellocr.comun.UsuarioAutenticado;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

/** Spec 7.1: el cliente manda un token nuevo antes de que venza el del CONNECT y la sesión sigue abierta. */
@Service
public class RenovacionSesion {

    private final AutenticacionStomp autenticacion;
    private final RegistroSesiones registro;

    public RenovacionSesion(AutenticacionStomp autenticacion, RegistroSesiones registro) {
        this.autenticacion = autenticacion;
        this.registro = registro;
    }

    public void renovar(String sesionId, UUID usuarioId, String authorization) {
        Jwt jwt = autenticacion.decodificar(authorization)
                .filter(token -> UsuarioAutenticado.id(token).equals(usuarioId))
                .orElseThrow(() -> new ErrorNegocio(CodigoError.NO_AUTENTICADO,
                        "El token no es válido o no es de esta sesión."));
        registro.renovar(sesionId, usuarioId, jwt.getExpiresAt());
    }
}
