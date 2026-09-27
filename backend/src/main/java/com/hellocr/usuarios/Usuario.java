package com.hellocr.usuarios;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(name = "usuarios")
public class Usuario {

    /** Mismo formato que la restricción usuarios_nombre_usuario_formato. */
    public static final String FORMATO_NOMBRE_USUARIO = "^[a-z][a-z0-9_.]{2,19}$";
    public static final String MENSAJE_FORMATO_NOMBRE_USUARIO = "El nombre de usuario debe empezar con una letra y "
            + "tener de 3 a 20 caracteres: letras, números, punto o guion bajo.";

    @Id
    @GeneratedValue
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false, unique = true)
    private String correo;

    @Column(name = "nombre_usuario", nullable = false, unique = true, length = 20)
    private String nombreUsuario;

    @Column(name = "nombre_visible", nullable = false, length = 50)
    private String nombreVisible;

    @Column(name = "hash_contrasena", nullable = false, length = 100)
    private String hashContrasena;

    @Column(length = 140)
    private String info;

    @Column(name = "codigo_invitacion", nullable = false, unique = true, length = 8)
    private String codigoInvitacion;

    @Column(name = "correo_verificado_en")
    private Instant correoVerificadoEn;

    @Column(name = "creado_en", nullable = false)
    private Instant creadoEn;

    protected Usuario() {
    }

    public Usuario(String correo, String nombreUsuario, String nombreVisible, String hashContrasena,
            String codigoInvitacion, Instant creadoEn) {
        this.correo = normalizarCorreo(correo);
        this.nombreUsuario = normalizarNombreUsuario(nombreUsuario);
        this.nombreVisible = nombreVisible;
        this.hashContrasena = hashContrasena;
        this.codigoInvitacion = codigoInvitacion;
        this.creadoEn = creadoEn;
    }

    /** Sin espacios alrededor y en minúsculas, como lo exige la base. */
    public static String normalizarCorreo(String correo) {
        return correo.trim().toLowerCase(Locale.ROOT);
    }

    /** Sin espacios, sin la @ inicial que la gente suele escribir y en minúsculas. */
    public static String normalizarNombreUsuario(String nombreUsuario) {
        String limpio = nombreUsuario.trim();
        if (limpio.startsWith("@")) {
            limpio = limpio.substring(1);
        }
        return limpio.toLowerCase(Locale.ROOT);
    }

    public boolean correoVerificado() {
        return correoVerificadoEn != null;
    }

    /** Marca el correo como verificado; si ya lo estaba, conserva la fecha original. */
    public void verificarCorreo(Instant ahora) {
        if (correoVerificadoEn == null) {
            correoVerificadoEn = ahora;
        }
    }

    public void cambiarContrasena(String hashContrasena) {
        this.hashContrasena = hashContrasena;
    }

    public void cambiarNombreVisible(String nombreVisible) {
        this.nombreVisible = nombreVisible;
    }

    public void cambiarInfo(String info) {
        this.info = info;
    }

    public void cambiarNombreUsuario(String nombreUsuario) {
        this.nombreUsuario = normalizarNombreUsuario(nombreUsuario);
    }

    public void cambiarCodigoInvitacion(String codigoInvitacion) {
        this.codigoInvitacion = codigoInvitacion;
    }

    public UUID getId() {
        return id;
    }

    public String getCorreo() {
        return correo;
    }

    public String getNombreUsuario() {
        return nombreUsuario;
    }

    public String getNombreVisible() {
        return nombreVisible;
    }

    public String getHashContrasena() {
        return hashContrasena;
    }

    public String getInfo() {
        return info;
    }

    public String getCodigoInvitacion() {
        return codigoInvitacion;
    }

    public Instant getCorreoVerificadoEn() {
        return correoVerificadoEn;
    }

    public Instant getCreadoEn() {
        return creadoEn;
    }
}
