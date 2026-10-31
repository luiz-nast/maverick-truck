// Os vídeos motivacionais da tela de bloqueio: clipes de gente de peso (Elon Musk, Guto Galamba, O Lobo de Wall Street),
// tocados pelo player oficial do YouTube a partir do canal de quem publicou (o dono, 10-31: discurso de vídeo de verdade,
// não a voz do celular). Nenhum vídeo fica dentro do app nem no GitHub, só o endereço: o direito continua com o dono do
// canal, que ganha a visualização. Tocam sempre a pelo menos 40% do volume de mídia; se o celular estiver mais alto,
// fica como está.
package io.github.luiz_nast.maverick.truck

import kotlin.math.ceil

/** Um clipe: o id dele no YouTube e de quem é (o canal oficial que publicou). */
data class Video(val id: String, val quem: String, val canal: String)

/** A fila de clipes, todos de canais oficiais e com a incorporação liberada pelo dono. */
val VIDEOS = listOf(
    Video("pzLQLpEDjvc", "The Wolf of Wall Street", "Paramount Plus"),
    Video("e7Qh-vwpYH8", "Elon Musk, USC 2014", "USC"),
    Video("JDd9CGZsABQ", "Guto Galamba", "oanimal.social"),
    Video("KoihlAl7ugQ", "Elon Musk", "Lex Clips"),
    Video("oz4TO7YxQRc", "The Wolf of Wall Street", "Paramount Pictures"),
    Video("-I8-EZuHEcY", "Guto Galamba", "oanimal.social"),
    Video("M-ZH3psUbfU", "Elon Musk", "Lex Clips"),
    Video("ViEjSgM4hIs", "The Wolf of Wall Street", "Paramount Pictures"),
    Video("mfo8OPYrsnw", "Guto Galamba", "oanimal.social"),
    Video("bRmpxFGdBdE", "Guto Galamba", "oanimal.social"),
)

/** A fila começando no clipe [inicio] (cada vez que a tela aparece, começa num diferente) e dando a volta. */
fun filaDeVideos(inicio: Int): List<Video> {
    val i = Math.floorMod(inicio, VIDEOS.size)
    return VIDEOS.drop(i) + VIDEOS.take(i)
}

/** O volume de mídia mínimo dos vídeos, em fração do máximo. */
const val VOLUME_MINIMO_DOS_VIDEOS = 0.4f

/**
 * O volume em que os vídeos tocam: o [atual] do celular, ou [VOLUME_MINIMO_DOS_VIDEOS] do [maximo] se o atual for
 * menor. Nunca abaixa o volume de ninguém.
 */
fun volumeDosVideos(atual: Int, maximo: Int): Int =
    maxOf(atual, ceil(maximo * VOLUME_MINIMO_DOS_VIDEOS).toInt()).coerceAtMost(maximo)
