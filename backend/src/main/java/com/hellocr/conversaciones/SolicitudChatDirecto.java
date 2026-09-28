package com.hellocr.conversaciones;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record SolicitudChatDirecto(@NotNull(message = "Falta la persona con quien chatear.") UUID usuarioId) {
}
