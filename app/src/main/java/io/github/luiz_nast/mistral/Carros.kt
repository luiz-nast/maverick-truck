// As fotos do carrossel da tela de bloqueio: carros muito luxuosos, do Wikimedia Commons, com licença livre (CC BY ou
// CC BY-SA). A tela de bloqueio mostra só a foto (o dono, 10-31: nada de texto embaixo); o carro, o autor e a licença,
// que as licenças pedem, ficam na tela do app ("Photo credits") e no CREDITS.md, com os links das licenças e das fontes.
// Conferido na ficha oficial de cada arquivo no Commons (31-10-2026): só obra
// do próprio autor que subiu, ou do Flickr com a revisão do Commons. Sem carro antigo, nem no fundo da foto (o dono, 10-31).
package io.github.luiz_nast.mistral

/** Uma foto do carrossel: o [desenho] (R.drawable) e a [legenda] com o carro, o autor e a licença. */
data class Carro(val desenho: Int, val legenda: String)

/** Onde ficam os links das licenças e das fontes de cada foto (a tela do app mostra embaixo dos créditos). */
const val CREDITOS = "Licenses & sources: github.com/luiz-nast/maverick-truck/blob/main/CREDITS.md"

val CARROS = listOf(
    Carro(R.drawable.carro_02, "Rolls-Royce Cullinan · Photo: Tristan Surtel · CC BY-SA 4.0"),
    Carro(R.drawable.carro_03, "Bugatti Chiron · Photo: Calreyn88 · CC BY-SA 4.0"),
    Carro(R.drawable.carro_04, "Bugatti Divo · Photo: MrWalkr · CC BY-SA 4.0"),
    Carro(R.drawable.carro_05, "Pagani Huayra · Photo: Andrew Basterfield · CC BY-SA 2.0"),
    Carro(R.drawable.carro_06, "Pagani Zonda · Photo: ejcallow (Flickr) · CC BY 2.0"),
    Carro(R.drawable.carro_08, "Koenigsegg Regera · Photo: Ank Kumar · CC BY-SA 4.0"),
    Carro(R.drawable.carro_09, "Lamborghini Aventador SVJ · Photo: Calreyn88 · CC BY-SA 4.0"),
    Carro(R.drawable.carro_10, "Ferrari LaFerrari · Photo: Axion23 · CC BY 2.0"),
    Carro(R.drawable.carro_12, "Aston Martin Valkyrie · Photo: Vauxford · CC BY-SA 4.0"),
)
