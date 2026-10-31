// As fotos do carrossel da tela de bloqueio: carros muito luxuosos, do Wikimedia Commons, com licença livre (CC BY ou
// CC BY-SA). Cada uma leva embaixo o carro, o autor e a licença, como as licenças pedem, e o endereço do CREDITS.md, que
// tem os links das licenças e das fontes. Conferido na ficha oficial de cada arquivo no Commons (31-10-2026): só obra
// do próprio autor que subiu, ou do Flickr com a revisão do Commons; a P1 de outro fotógrafo sem autorização saiu.
package io.github.luiz_nast.maverick.truck

/** Uma foto do carrossel: o [desenho] (R.drawable) e a [legenda] com o carro, o autor e a licença. */
data class Carro(val desenho: Int, val legenda: String)

/** Onde ficam os links das licenças e das fontes de cada foto (a tela mostra embaixo da legenda). */
const val CREDITOS = "Licenses & sources: github.com/luiz-nast/maverick-truck/blob/main/CREDITS.md"

val CARROS = listOf(
    Carro(R.drawable.carro_01, "Rolls-Royce Phantom · Photo: Dietmar Rabich · CC BY-SA 4.0"),
    Carro(R.drawable.carro_02, "Rolls-Royce Cullinan · Photo: Tristan Surtel · CC BY-SA 4.0"),
    Carro(R.drawable.carro_03, "Bugatti Chiron · Photo: Calreyn88 · CC BY-SA 4.0"),
    Carro(R.drawable.carro_04, "Bugatti Divo · Photo: MrWalkr · CC BY-SA 4.0"),
    Carro(R.drawable.carro_05, "Pagani Huayra · Photo: Andrew Basterfield · CC BY-SA 2.0"),
    Carro(R.drawable.carro_06, "Pagani Zonda · Photo: ejcallow (Flickr) · CC BY 2.0"),
    Carro(R.drawable.carro_07, "Koenigsegg Jesko · Photo: Calreyn88 · CC BY-SA 4.0"),
    Carro(R.drawable.carro_08, "Koenigsegg Regera · Photo: Ank Kumar · CC BY-SA 4.0"),
    Carro(R.drawable.carro_09, "Lamborghini Aventador SVJ · Photo: Calreyn88 · CC BY-SA 4.0"),
    Carro(R.drawable.carro_10, "Ferrari LaFerrari · Photo: Axion23 · CC BY 2.0"),
    Carro(R.drawable.carro_11, "McLaren P1 · Photo: MrWalkr · CC BY-SA 4.0"),
    Carro(R.drawable.carro_12, "Aston Martin Valkyrie · Photo: Vauxford · CC BY-SA 4.0"),
)
