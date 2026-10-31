// O discurso da tela de bloqueio: escrito pra este projeto (MIT, como o código), lido pela voz do próprio celular. Toca
// sempre a pelo menos 40% do volume de mídia (o dono, 10-31): se o celular estiver mais alto, fica como está.
package io.github.luiz_nast.maverick.truck

import kotlin.math.ceil

/** O volume de mídia mínimo do discurso, em fração do máximo. */
const val VOLUME_MINIMO_DO_DISCURSO = 0.4f

/** O texto que a voz lê quando a tela de bloqueio aparece (em inglês, como o resto do app). */
const val DISCURSO = "Stop. Look at these cars. Nobody who owns one of them got it by scrolling. " +
    "Every one of them was built by people who chose the hard hour over the easy one, again and again, " +
    "when nobody was watching. Your seven minutes are up. The feed will still be here in fifteen minutes. " +
    "Your time will not. Put the phone down. Pick up the one thing that moves your life forward: " +
    "the project, the book, the workout, the work. Do it badly if you must, but do it now. " +
    "Discipline is choosing what you want most over what you want now. " +
    "You don't need more motivation. You need the next small action. Take it. Then take the next one. " +
    "That is how every one of these machines was earned. Go."

/**
 * O volume em que o discurso toca: o [atual] do celular, ou [VOLUME_MINIMO_DO_DISCURSO] do [maximo] se o atual for
 * menor. Nunca abaixa o volume de ninguém.
 */
fun volumeDoDiscurso(atual: Int, maximo: Int): Int =
    maxOf(atual, ceil(maximo * VOLUME_MINIMO_DO_DISCURSO).toInt()).coerceAtMost(maximo)
