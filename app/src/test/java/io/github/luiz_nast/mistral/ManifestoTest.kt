package io.github.luiz_nast.mistral

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

// O que o Manifest promete (os testes rodam na pasta app/): a permissão USO de assinatura e o provider que o Chiron lê.
class ManifestoTest {
    private val manifesto = File("src/main/AndroidManifest.xml").readText()
    private val uso = "io.github.luiz_nast.mistral.permission.USO"

    private fun tag(nome: String, chave: String) =
        Regex("<$nome\\b[^>]*android:name=\"$chave\"[^>]*>").find(manifesto)?.value ?: error("$nome $chave não está no manifesto")

    @Test
    fun usoEPermissaoDeAssinatura() {
        assertTrue(tag("permission", uso).contains("android:protectionLevel=\"signature\""))
    }

    @Test
    fun fonteDoUsoSoLeComAPermissao() {
        val p = tag("provider", ".FonteDoUso")
        assertTrue(p.contains("android:authorities=\"io.github.luiz_nast.mistral.uso\""))
        assertTrue(p.contains("android:exported=\"true\""))
        assertTrue(p.contains("android:readPermission=\"$uso\""))
        assertTrue(p.contains("android:writePermission=\"$uso\""))
    }

    @Test
    fun aSonsContinuaDeclaradaEUsada() {
        val sons = "com.implacavel.alarme.permission.SONS"
        assertTrue(tag("permission", sons).contains("android:protectionLevel=\"signature\""))
        assertTrue(tag("uses-permission", sons).isNotEmpty())
    }
}
