package com.hellocr.auth;

import com.hellocr.comun.CodigoError;
import com.hellocr.comun.ErrorNegocio;
import com.hellocr.comun.TokensSeguros;
import com.hellocr.config.JwtProperties;
import com.hellocr.usuarios.Usuario;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RefreshTokenService {

    /**
     * Si un token ya reemplazado por la rotación se reusa dentro de este margen, se asume que dos pestañas
     * refrescaron a la vez y solo se rechaza ese request. Pasado el margen, se trata como robo y se cierran
     * todas las sesiones. Un token revocado por logout o por un cierre masivo solo se rechaza: si también
     * provocara una cascada, dos dispositivos con cookies viejas se cerrarían la sesión uno al otro sin fin.
     */
    static final Duration GRACIA_REUTILIZACION = Duration.ofSeconds(30);

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);

    private final RefreshTokenRepository tokens;
    private final JwtProperties propiedades;
    private final ApplicationEventPublisher eventos;
    private final Clock clock;

    public RefreshTokenService(RefreshTokenRepository tokens, JwtProperties propiedades,
            ApplicationEventPublisher eventos, Clock clock) {
        this.tokens = tokens;
        this.propiedades = propiedades;
        this.eventos = eventos;
        this.clock = clock;
    }

    public record Rotacion(Usuario usuario, String nuevoToken) {
    }

    /** Crea un refresh token que vence en 30 días y devuelve su valor en claro (en la base queda el hash). */
    @Transactional
    public String emitir(Usuario usuario) {
        String token = TokensSeguros.generar();
        Instant ahora = clock.instant();
        tokens.save(new RefreshToken(usuario, TokensSeguros.hash(token), ahora.plus(propiedades.duracionRefresh()), ahora));
        return token;
    }

    /** Revoca el token recibido y emite uno nuevo. noRollbackFor: la revocación masiva debe persistir. */
    @Transactional(noRollbackFor = ErrorNegocio.class)
    public Rotacion rotar(String token) {
        Instant ahora = clock.instant();
        RefreshToken actual = buscar(token).orElseThrow(RefreshTokenService::sesionInvalida);
        if (actual.revocado()) {
            if (actual.reemplazado() && actual.getReemplazadoEn().plus(GRACIA_REUTILIZACION).isBefore(ahora)) {
                revocarTodos(actual.getUsuario().getId());
                log.warn("Reutilización de refresh token: se cerraron las sesiones del usuario {}",
                        actual.getUsuario().getId());
            }
            throw sesionInvalida();
        }
        if (actual.vencido(ahora)) {
            throw sesionInvalida();
        }
        actual.reemplazar(ahora);
        return new Rotacion(actual.getUsuario(), emitir(actual.getUsuario()));
    }

    @Transactional
    public void revocar(String token) {
        buscar(token).ifPresent(encontrado -> encontrado.revocar(clock.instant()));
    }

    /** Cierra todas las sesiones del usuario y avisa con SesionesRevocadas. */
    @Transactional
    public void revocarTodos(UUID usuarioId) {
        tokens.revocarTodosDe(usuarioId, clock.instant());
        eventos.publishEvent(new SesionesRevocadas(usuarioId));
    }

    /** Todos los días a las 4:00. Los revocados se conservan hasta vencer para detectar reutilizaciones. */
    @Scheduled(cron = "0 0 4 * * *", zone = "${app.zona-horaria}")
    @Transactional
    public int limpiarVencidos() {
        return tokens.borrarVencidos(clock.instant());
    }

    private Optional<RefreshToken> buscar(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        return tokens.findByTokenHash(TokensSeguros.hash(token));
    }

    private static ErrorNegocio sesionInvalida() {
        return new ErrorNegocio(CodigoError.NO_AUTENTICADO, "La sesión expiró. Iniciá sesión de nuevo.");
    }
}
