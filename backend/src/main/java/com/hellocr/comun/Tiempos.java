package com.hellocr.comun;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/** Instantes para las consultas SQL de conversaciones y mensajes. */
public final class Tiempos {

    private Tiempos() {
    }

    /** PostgreSQL guarda microsegundos: truncar evita que un instante se vea distinto antes y después de guardarlo. */
    public static Instant ahora(Clock clock) {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    public static OffsetDateTime sql(Instant instante) {
        return OffsetDateTime.ofInstant(instante, ZoneOffset.UTC);
    }

    public static Instant instante(ResultSet fila, String columna) throws SQLException {
        OffsetDateTime valor = fila.getObject(columna, OffsetDateTime.class);
        return valor == null ? null : valor.toInstant();
    }
}
