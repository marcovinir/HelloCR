package com.hellocr.correo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.hellocr.soporte.PruebaIntegracion;
import com.hellocr.usuarios.Usuario;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

class DespachadorCorreoTest extends PruebaIntegracion {

    @Autowired
    private CorreosDeCuenta correos;
    @Autowired
    private TransactionTemplate transacciones;
    @Autowired
    private CorreoProperties propiedades;

    @Test
    void elCorreoSaleRecienDespuesDelCommit() {
        Usuario ana = crearUsuario("ana");

        transacciones.executeWithoutResult(estado -> {
            correos.enviarVerificacion(ana, "tok");
            assertThat(buzon.todos()).as("todavía dentro de la transacción").isEmpty();
        });

        assertThat(buzon.todos()).singleElement()
                .extracting(CorreoSaliente::destinatario).isEqualTo("ana@correo.cr");
    }

    @Test
    void siLaTransaccionSeRevierteElCorreoNoSale() {
        Usuario ana = crearUsuario("ana");

        transacciones.executeWithoutResult(estado -> {
            correos.enviarRecuperacion(ana, "tok");
            estado.setRollbackOnly();
        });

        assertThat(buzon.todos()).isEmpty();
    }

    @Test
    void unFalloDelServidorDeCorreoNoSePropaga() {
        DespachadorCorreo despachador = new DespachadorCorreo(correo -> {
            throw new IllegalStateException("SMTP caído");
        }, propiedades);

        assertThatCode(() -> despachador.alConfirmar(
                new CorreoPendiente(new CorreoSaliente("ana@correo.cr", "Asunto", "texto", "<p>html</p>"))))
                .doesNotThrowAnyException();
    }
}
