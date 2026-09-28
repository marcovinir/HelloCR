package com.hellocr.conversaciones;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hellocr.soporte.PruebaIntegracion;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Tabla de casos de la visibilidad (spec, sección 12): en un grupo con mensajes 1..10,
 * Ana está desde el principio, Luis entra en el 4, Sofía sale en el 6 y Marco sale en el 3 y vuelve en el 8.
 */
class MembresiaTest extends PruebaIntegracion {

    @Autowired
    private ConversacionRepository conversaciones;
    @Autowired
    private ConsultaMembresia consulta;
    @Autowired
    private GestionMiembros gestion;
    @Autowired
    private TransactionTemplate transacciones;

    private UUID grupo;
    private UUID ana;
    private UUID luis;
    private UUID sofia;
    private UUID marco;

    @BeforeEach
    void escenario() {
        ana = crearUsuario("ana").getId();
        luis = crearUsuario("luis").getId();
        sofia = crearUsuario("sofia").getId();
        marco = crearUsuario("marco").getId();
        grupo = conversaciones.crear(TipoConversacion.GRUPO, reloj.instant());
        gestion.incorporar(grupo, ana, Rol.ADMIN, 1);
        gestion.incorporar(grupo, sofia, Rol.MIEMBRO, 1);
        gestion.incorporar(grupo, marco, Rol.MIEMBRO, 1);
        for (long secuencia = 1; secuencia <= 10; secuencia++) {
            jdbc.update("""
                    INSERT INTO mensajes (conversacion_id, secuencia, remitente_id, id_cliente, tipo, texto)
                    VALUES (?, ?, ?, ?, 'TEXTO', 'hola')
                    """, grupo, secuencia, ana, UUID.randomUUID());
        }
        gestion.retirar(grupo, marco, 3);
        gestion.incorporar(grupo, luis, Rol.MIEMBRO, 4);
        gestion.retirar(grupo, sofia, 6);
        gestion.incorporar(grupo, marco, Rol.MIEMBRO, 8);
    }

    @Test
    void cadaUnoVeSoloLasSecuenciasDeSusPeriodos() {
        assertThat(visibles(ana)).containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L);
        assertThat(visibles(luis)).containsExactly(4L, 5L, 6L, 7L, 8L, 9L, 10L);
        assertThat(visibles(sofia)).containsExactly(1L, 2L, 3L, 4L, 5L, 6L);
        assertThat(visibles(marco)).containsExactly(1L, 2L, 3L, 8L, 9L, 10L);
    }

    @Test
    void quienesPuedenVerUnaSecuencia() {
        assertThat(consulta.quienesPuedenVer(grupo, 5)).containsExactlyInAnyOrder(ana, luis, sofia);
        assertThat(consulta.quienesPuedenVer(grupo, 7)).containsExactlyInAnyOrder(ana, luis);
        assertThat(consulta.quienesPuedenVer(grupo, 9)).containsExactlyInAnyOrder(ana, luis, marco);
    }

    @Test
    void losActivosSonLosQueTienenUnPeriodoAbierto() {
        assertThat(consulta.miembrosActivos(grupo)).containsExactlyInAnyOrder(ana, luis, marco);
        assertThat(consulta.esMiembroActivo(grupo, sofia)).isFalse();
        assertThat(consulta.fueMiembro(grupo, sofia)).isTrue();
        assertThat(consulta.fueMiembro(grupo, UUID.randomUUID())).isFalse();
        assertThat(gestion.contarActivos(grupo)).isEqualTo(3);
    }

    @Test
    void noSePuedeAbrirUnSegundoPeriodoSinCerrarElPrimero() {
        assertThatThrownBy(() -> gestion.incorporar(grupo, ana, Rol.MIEMBRO, 11))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void alRetirarseElRolVuelveAMiembroYElMasAntiguoEsElDelPeriodoMasViejo() {
        gestion.cambiarRol(grupo, luis, Rol.ADMIN);
        assertThat(gestion.contarAdministradoresActivos(grupo)).isEqualTo(2);

        gestion.retirar(grupo, luis, 11);

        assertThat(gestion.rolActivo(grupo, luis)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT rol FROM miembros WHERE conversacion_id = ? AND usuario_id = ?",
                String.class, grupo, luis)).isEqualTo("MIEMBRO");
        assertThat(gestion.contarAdministradoresActivos(grupo)).isEqualTo(1);
        assertThat(gestion.rolActivo(grupo, ana)).contains(Rol.ADMIN);
        assertThat(gestion.activoMasAntiguo(grupo)).contains(ana);
    }

    @Test
    void bloquearDevuelveElTipoOVacioSiNoExiste() {
        Optional<TipoConversacion> existente = transacciones.execute(estado -> conversaciones.bloquear(grupo));
        Optional<TipoConversacion> inexistente =
                transacciones.execute(estado -> conversaciones.bloquear(UUID.randomUUID()));

        assertThat(existente).contains(TipoConversacion.GRUPO);
        assertThat(inexistente).isEmpty();
    }

    private List<Long> visibles(UUID usuario) {
        return LongStream.rangeClosed(1, 10).filter(secuencia -> consulta.puedeVer(grupo, usuario, secuencia))
                .boxed().toList();
    }
}
