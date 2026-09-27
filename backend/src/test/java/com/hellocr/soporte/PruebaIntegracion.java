package com.hellocr.soporte;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hellocr.auth.LimiteIntentosLogin;
import com.hellocr.usuarios.GeneradorCodigos;
import com.hellocr.usuarios.Usuario;
import com.hellocr.usuarios.UsuarioRepository;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Base de los tests de integración: contexto completo, base hellocr_test limpia, reloj fijo y buzón vacío. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PruebasConfig.class)
public abstract class PruebaIntegracion {

    public static final String CLAVE = "clave12345";

    private static final String TABLAS = "tokens_correo, refresh_tokens, usuarios";
    private static final PasswordEncoder CODIFICADOR = PasswordEncoderFactories.createDelegatingPasswordEncoder();

    @Autowired
    protected MockMvc mvc;
    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected RelojAjustable reloj;
    @Autowired
    protected BuzonPrueba buzon;
    @Autowired
    private UsuarioRepository usuarios;
    @Autowired
    private GeneradorCodigos codigos;
    @Autowired
    private LimiteIntentosLogin limiteIntentos;

    @BeforeEach
    void reiniciarEstado() {
        reloj.reiniciar();
        buzon.vaciar();
        limiteIntentos.olvidarTodo();
        jdbc.execute("TRUNCATE " + TABLAS + " RESTART IDENTITY CASCADE");
    }

    /** Cuenta verificada con correo <nombreUsuario>@correo.cr y contraseña CLAVE. */
    protected Usuario crearUsuario(String nombreUsuario) {
        return guardar(nombreUsuario, true);
    }

    protected Usuario crearUsuarioSinVerificar(String nombreUsuario) {
        return guardar(nombreUsuario, false);
    }

    private Usuario guardar(String nombreUsuario, boolean verificado) {
        Usuario usuario = new Usuario(nombreUsuario + "@correo.cr", nombreUsuario, "Usuario " + nombreUsuario,
                CODIFICADOR.encode(CLAVE), codigos.codigoInvitacion(), reloj.instant());
        if (verificado) {
            usuario.verificarCorreo(reloj.instant());
        }
        return usuarios.save(usuario);
    }

    protected SesionPrueba iniciarSesion(String nombreUsuario) throws Exception {
        return SesionPrueba.de(mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"identificador": "%s", "contrasena": "%s"}
                                """.formatted(nombreUsuario, CLAVE)))
                .andExpect(status().isOk())
                .andReturn());
    }

    /** Atajo: crea la cuenta verificada y abre sesión. */
    protected SesionPrueba sesionDe(String nombreUsuario) throws Exception {
        crearUsuario(nombreUsuario);
        return iniciarSesion(nombreUsuario);
    }

    protected static RequestPostProcessor con(SesionPrueba sesion) {
        return solicitud -> {
            solicitud.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + sesion.accessToken());
            return solicitud;
        };
    }

    /** Lanza las tareas en hilos distintos, todas a la vez, y devuelve sus resultados en orden. */
    protected static <T> List<T> enParalelo(List<Callable<T>> tareas) throws Exception {
        ExecutorService hilos = Executors.newFixedThreadPool(tareas.size());
        try {
            CountDownLatch largada = new CountDownLatch(1);
            List<Future<T>> futuros = new ArrayList<>();
            for (Callable<T> tarea : tareas) {
                futuros.add(hilos.submit(() -> {
                    largada.await();
                    return tarea.call();
                }));
            }
            largada.countDown();
            List<T> resultados = new ArrayList<>();
            for (Future<T> futuro : futuros) {
                resultados.add(futuro.get(30, TimeUnit.SECONDS));
            }
            return resultados;
        } finally {
            hilos.shutdownNow();
        }
    }

    protected static <T> List<T> enParalelo(int veces, Callable<T> tarea) throws Exception {
        return enParalelo(Collections.nCopies(veces, tarea));
    }
}
