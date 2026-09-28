package com.hellocr.conversaciones;

import java.util.List;
import java.util.UUID;

/** Spec 9.2. Incluye a los ex miembros; la interfaz solo lista a los activos. El plan 3 agrega fotoId. */
public record ConversacionDetalle(UUID id, TipoConversacion tipo, String titulo, String descripcion, boolean activa,
        Rol miRol, List<MiembroDetalle> miembros) {
}
