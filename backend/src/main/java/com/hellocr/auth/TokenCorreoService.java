package com.hellocr.auth;

import com.hellocr.comun.CodigoError;
import com.hellocr.comun.ErrorNegocio;
import com.hellocr.comun.TokensSeguros;
import com.hellocr.correo.CorreoProperties;
import com.hellocr.usuarios.Usuario;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TokenCorreoService {

    private final TokenCorreoRepository tokens;
    private final CorreoProperties propiedades;
    private final Clock clock;

    public TokenCorreoService(TokenCorreoRepository tokens, CorreoProperties propiedades, Clock clock) {
        this.tokens = tokens;
        this.propiedades = propiedades;
        this.clock = clock;
    }

    /** Crea un token nuevo (el anterior del mismo propósito deja de servir) y devuelve su valor en claro. */
    @Transactional
    public String emitir(Usuario usuario, PropositoToken proposito) {
        Instant ahora = clock.instant();
        tokens.invalidarVigentes(usuario.getId(), proposito, ahora);
        String token = TokensSeguros.generar();
        tokens.save(new TokenCorreo(usuario, proposito, TokensSeguros.hash(token), ahora.plus(duracion(proposito)),
                ahora));
        return token;
    }

    /** Evita mandar correos seguidos: exige app.correo.espera-reenvio desde el último del mismo propósito. */
    @Transactional(readOnly = true)
    public boolean puedeEmitir(Usuario usuario, PropositoToken proposito) {
        return esperaParaEmitir(usuario, proposito).isZero();
    }

    /** Cuánto falta para poder mandar otro correo de ese propósito; cero si ya se puede. */
    @Transactional(readOnly = true)
    public Duration esperaParaEmitir(Usuario usuario, PropositoToken proposito) {
        Instant ahora = clock.instant();
        return tokens.findFirstByUsuario_IdAndPropositoOrderByCreadoEnDesc(usuario.getId(), proposito)
                .map(ultimo -> ultimo.getCreadoEn().plus(propiedades.esperaReenvio()))
                .filter(ahora::isBefore)
                .map(desde -> Duration.between(ahora, desde))
                .orElse(Duration.ZERO);
    }

    /** Marca el token como usado y devuelve su usuario. Llamar dentro de la transacción que lo va a modificar. */
    @Transactional
    public Usuario consumir(String token, PropositoToken proposito) {
        if (token == null || token.isBlank()) {
            throw tokenInvalido();
        }
        Instant ahora = clock.instant();
        TokenCorreo encontrado = tokens.bloquearPorHash(TokensSeguros.hash(token))
                .filter(candidato -> candidato.sirvePara(proposito, ahora))
                .orElseThrow(TokenCorreoService::tokenInvalido);
        encontrado.usar(ahora);
        return encontrado.getUsuario();
    }

    /** Todos los días a las 4:00. Ningún token dura más de 24 h, así que los usados también terminan acá. */
    @Scheduled(cron = "0 0 4 * * *", zone = "${app.zona-horaria}")
    @Transactional
    public int limpiarVencidos() {
        return tokens.borrarVencidos(clock.instant());
    }

    private Duration duracion(PropositoToken proposito) {
        return switch (proposito) {
            case VERIFICACION -> propiedades.duracionVerificacion();
            case RECUPERACION -> propiedades.duracionRecuperacion();
        };
    }

    private static ErrorNegocio tokenInvalido() {
        return new ErrorNegocio(CodigoError.TOKEN_INVALIDO, "El enlace no es válido o ya venció. Pedí uno nuevo.");
    }
}
