package com.hellocr.correo;

/** Entrega un correo. En desarrollo lo escribe en el log; en producción lo manda por SMTP. */
public interface EnviadorCorreo {

    void enviar(CorreoSaliente correo);
}
