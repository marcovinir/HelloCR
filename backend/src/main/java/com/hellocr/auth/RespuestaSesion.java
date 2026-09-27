package com.hellocr.auth;

import com.hellocr.usuarios.UsuarioPropio;

public record RespuestaSesion(String accessToken, UsuarioPropio usuario) {
}
