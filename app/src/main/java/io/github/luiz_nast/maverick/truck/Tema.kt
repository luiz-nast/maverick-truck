// As cores, os tamanhos de texto, os raios e os espaços do app num lugar só: mudar uma medida é mudar uma linha.
// A mesma escala do Tourbillon Alarm e do Chiron (11-07), pra os três apps terem a mesma proporção.
package io.github.luiz_nast.maverick.truck

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// A paleta dos quatro projetos do dono (11-02): 0B0E12, 27313A, 4E5158, 7B848C, 949AA0, 8F2E1D; mais dois tons derivados
// do vermelho-ferrugem (pra texto e anéis sobre o escuro) e um branco-gelo pros títulos
internal val Fundo = Color(0xFF0B0E12)
internal val Cartao = Color(0xFF27313A)
internal val Linha = Color(0xFF4E5158) // bordas e divisórias
internal val Destaque = Color(0xFF8F2E1D) // preenchimento (botões)
internal val DestaqueClaro = Color(0xFFC4472E) // texto, anéis e traços sobre o escuro
internal val Vermelho = Color(0xFFE0654A) // perigo, bloqueado
internal val Corpo = Color(0xFF949AA0) // texto secundário
internal val Apagado = Color(0xFF7B848C) // legenda e rótulo de seção
internal val Texto = Color(0xFFECEEF0)

// Os textos: título de tela 28, subtela 22, cartão 18, corpo 16, secundário 14, legenda 12, rótulo 13 em CAIXA ALTA
private val base = Typography()
val Tipo = Typography(
    headlineMedium = base.headlineMedium.copy(fontSize = 28.sp, fontWeight = FontWeight.Black),
    headlineSmall = base.titleLarge.copy(fontSize = 22.sp, fontWeight = FontWeight.Black), // título do AlertDialog
    titleLarge = base.titleLarge.copy(fontSize = 22.sp, fontWeight = FontWeight.Black),
    titleMedium = base.titleMedium.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold),
    bodyLarge = base.bodyLarge, bodyMedium = base.bodyMedium, bodySmall = base.bodySmall,
    labelMedium = base.labelMedium.copy(fontSize = 13.sp, fontWeight = FontWeight.Bold),
)

// Cartão 20; campo, aviso e miniatura 12
val Formas = Shapes(small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(20.dp))

/** Os espaços: só 4, 8, 12, 16, 20 e 24. */
object Medida {
    val tela = 20.dp; val entreCartoes = 12.dp; val dentroCartao = 16.dp
    val antesDeSecao = 24.dp; val rotulo = 8.dp; val botao = 56.dp; val alvo = 48.dp
}
