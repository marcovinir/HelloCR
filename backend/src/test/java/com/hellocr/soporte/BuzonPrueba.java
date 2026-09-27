package com.hellocr.soporte;

import com.hellocr.correo.CorreoSaliente;
import com.hellocr.correo.EnviadorCorreo;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reemplaza al enviador real en los tests: guarda los correos en memoria. */
public class BuzonPrueba implements EnviadorCorreo {

    private static final Pattern TOKEN = Pattern.compile("token=([A-Za-z0-9_-]+)");

    private final List<CorreoSaliente> correos = new CopyOnWriteArrayList<>();

    @Override
    public void enviar(CorreoSaliente correo) {
        correos.add(correo);
    }

    public List<CorreoSaliente> todos() {
        return List.copyOf(correos);
    }

    public List<CorreoSaliente> para(String destinatario) {
        return correos.stream().filter(correo -> correo.destinatario().equals(destinatario)).toList();
    }

    public CorreoSaliente ultimoPara(String destinatario) {
        List<CorreoSaliente> recibidos = para(destinatario);
        if (recibidos.isEmpty()) {
            throw new AssertionError("No llegó ningún correo para " + destinatario);
        }
        return recibidos.getLast();
    }

    public void vaciar() {
        correos.clear();
    }

    /** El token del enlace que trae el correo. */
    public static String tokenDe(CorreoSaliente correo) {
        Matcher encontrado = TOKEN.matcher(correo.texto());
        if (!encontrado.find()) {
            throw new AssertionError("El correo no trae un enlace con token: " + correo.texto());
        }
        return encontrado.group(1);
    }
}
