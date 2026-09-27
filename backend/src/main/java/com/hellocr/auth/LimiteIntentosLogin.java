package com.hellocr.auth;

import com.hellocr.comun.CodigoError;
import com.hellocr.comun.ErrorNegocio;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Cuenta los logins fallidos por identificador + IP, en memoria (hay un solo servidor).
 * Los fallos se cuentan en una ventana de app.login.bloqueo desde el primero; al llegar al máximo,
 * la clave queda bloqueada ese mismo tiempo.
 */
@Component
public class LimiteIntentosLogin {

    private record Registro(int fallos, Instant venceEn, boolean bloqueado) {
    }

    private final Map<String, Registro> registros = new ConcurrentHashMap<>();
    private final LoginProperties propiedades;
    private final Clock clock;

    public LimiteIntentosLogin(LoginProperties propiedades, Clock clock) {
        this.propiedades = propiedades;
        this.clock = clock;
    }

    /** Lanza DEMASIADOS_INTENTOS si la clave está bloqueada. */
    public void verificar(String clave) {
        Instant ahora = clock.instant();
        Registro registro = registros.get(clave);
        if (registro != null && registro.bloqueado() && ahora.isBefore(registro.venceEn())) {
            throw bloqueada(Duration.between(ahora, registro.venceEn()));
        }
    }

    public void registrarFallo(String clave) {
        Instant ahora = clock.instant();
        registros.compute(clave, (k, actual) -> {
            Registro vigente = actual == null || !ahora.isBefore(actual.venceEn())
                    ? new Registro(0, ahora.plus(propiedades.bloqueo()), false)
                    : actual;
            int fallos = vigente.fallos() + 1;
            return fallos >= propiedades.maxIntentos()
                    ? new Registro(fallos, ahora.plus(propiedades.bloqueo()), true)
                    : new Registro(fallos, vigente.venceEn(), false);
        });
    }

    public void registrarExito(String clave) {
        registros.remove(clave);
    }

    @Scheduled(fixedDelay = 1, timeUnit = TimeUnit.HOURS)
    public void olvidarVencidos() {
        Instant ahora = clock.instant();
        registros.values().removeIf(registro -> !ahora.isBefore(registro.venceEn()));
    }

    /** Solo para tests: el estado vive en memoria y se comparte entre tests del mismo contexto. */
    public void olvidarTodo() {
        registros.clear();
    }

    int registrados() {
        return registros.size();
    }

    private static ErrorNegocio bloqueada(Duration falta) {
        long segundos = Math.max(1, (falta.toMillis() + 999) / 1000);
        long minutos = (segundos + 59) / 60;
        String cuando = minutos == 1 ? "1 minuto" : minutos + " minutos";
        return new ErrorNegocio(CodigoError.DEMASIADOS_INTENTOS,
                "Demasiados intentos fallidos. Probá de nuevo en " + cuando + ".",
                Map.of(ErrorNegocio.REINTENTAR_EN_SEGUNDOS, segundos));
    }
}
