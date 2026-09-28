package com.hellocr.mensajes;

import com.hellocr.comun.ErrorNegocio;
import com.hellocr.conversaciones.ConsultaMembresia;
import com.hellocr.conversaciones.ErroresConversacion;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class HistorialService {

    static final int LIMITE_POR_DEFECTO = 50;
    static final int LIMITE_MAXIMO = 200;

    private final ConsultaMembresia membresia;
    private final HistorialRepository historial;

    public HistorialService(ConsultaMembresia membresia, HistorialRepository historial) {
        this.membresia = membresia;
        this.historial = historial;
    }

    /** Pide uno más de lo necesario para saber si hay más sin contar todo. */
    @Transactional(readOnly = true)
    public PaginaMensajes pagina(UUID usuarioId, UUID conversacionId, Long antesDe, Long despuesDe, Integer limite) {
        int cantidad = limite == null ? LIMITE_POR_DEFECTO : limite;
        if (cantidad < 1 || cantidad > LIMITE_MAXIMO) {
            throw ErrorNegocio.validacion("limite", "El límite tiene que estar entre 1 y 200.");
        }
        if (antesDe != null && despuesDe != null) {
            throw ErrorNegocio.validacion("antesDe", "Pedí mensajes antesDe o despuesDe, no los dos.");
        }
        if (!membresia.fueMiembro(conversacionId, usuarioId)) {
            throw ErroresConversacion.noEncontrada();
        }
        if (despuesDe != null) {
            List<MensajeDto> siguientes = historial.posteriores(conversacionId, usuarioId, despuesDe, cantidad + 1);
            return new PaginaMensajes(List.copyOf(siguientes.subList(0, Math.min(cantidad, siguientes.size()))),
                    siguientes.size() > cantidad);
        }
        List<MensajeDto> previos = historial.anteriores(conversacionId, usuarioId, antesDe, cantidad + 1);
        List<MensajeDto> pagina = new ArrayList<>(previos.subList(0, Math.min(cantidad, previos.size())));
        Collections.reverse(pagina);
        return new PaginaMensajes(List.copyOf(pagina), previos.size() > cantidad);
    }
}
