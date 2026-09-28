package com.hellocr.mensajes;

import com.hellocr.comun.CodigoError;
import com.hellocr.comun.ErrorNegocio;
import com.hellocr.conversaciones.ConsultaMembresia;
import com.hellocr.conversaciones.ErroresConversacion;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Spec 8.2: acuses de entregado y leído. */
@Service
public class MarcasService {

    private final ConsultaMembresia membresia;
    private final MarcasRepository marcas;
    private final ApplicationEventPublisher eventos;

    public MarcasService(ConsultaMembresia membresia, MarcasRepository marcas, ApplicationEventPublisher eventos) {
        this.membresia = membresia;
        this.marcas = marcas;
        this.eventos = eventos;
    }

    @Transactional
    public void entregados(UUID usuarioId, UUID conversacionId, Long hasta) {
        actualizar(usuarioId, conversacionId, hasta, false);
    }

    @Transactional
    public void leidos(UUID usuarioId, UUID conversacionId, Long hasta) {
        actualizar(usuarioId, conversacionId, hasta, true);
    }

    /** hasta se recorta a lo que el usuario puede ver: nunca quedan marcas por encima de los mensajes reales. */
    private void actualizar(UUID usuarioId, UUID conversacionId, Long hasta, boolean leido) {
        if (conversacionId == null || hasta == null) {
            throw new ErrorNegocio(CodigoError.VALIDACION, "Faltan la conversación o la secuencia.");
        }
        if (!membresia.esMiembroActivo(conversacionId, usuarioId)) {
            throw ErroresConversacion.noEsMiembro();
        }
        long limite = Math.min(hasta, marcas.maxVisible(conversacionId, usuarioId));
        if (limite <= 0) {
            return;
        }
        Optional<Marcas> nuevas = leido
                ? marcas.marcarLeidos(conversacionId, usuarioId, limite)
                : marcas.marcarEntregados(conversacionId, usuarioId, limite);
        nuevas.ifPresent(m -> eventos.publishEvent(
                new EstadoActualizado(conversacionId, usuarioId, m.ultimaEntregada(), m.ultimaLeida())));
    }
}
