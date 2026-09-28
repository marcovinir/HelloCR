package com.hellocr.conversaciones;

/** Tramo de secuencias que un miembro puede ver, inclusivo en los dos extremos. hasta null = sigue abierto. */
public record Periodo(long desde, Long hasta) {
}
