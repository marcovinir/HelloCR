package com.hellocr.grupos;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record SolicitudMiembro(@NotNull(message = "Falta la persona que querés agregar.") UUID usuarioId) {
}
