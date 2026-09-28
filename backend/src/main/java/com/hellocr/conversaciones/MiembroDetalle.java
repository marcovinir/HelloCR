package com.hellocr.conversaciones;

import com.hellocr.usuarios.PerfilPublico;
import java.util.List;

/** Un miembro actual o pasado, con sus marcas y periodos (el cliente calcula ✓✓ con esto, spec 8.3). */
public record MiembroDetalle(PerfilPublico usuario, Rol rol, long ultimaEntregada, long ultimaLeida,
        List<Periodo> periodos) {
}
