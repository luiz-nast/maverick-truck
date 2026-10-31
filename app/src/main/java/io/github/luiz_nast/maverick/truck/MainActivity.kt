// A tela do app: a rodada de cada site (o anel), o botão de ligar e desligar e as permissões. Quem conta e bloqueia
// é o escudo ([Escudo]); aqui só se desenha o [Estado].
package io.github.luiz_nast.maverick.truck

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay

private val Fundo = Color(0xFF0E0F12)
private val Cartao = Color(0xFF181A20)
private val Laranja = Color(0xFFFF7A1A)
private val Vermelho = Color(0xFFE5484D)
private val Verde = Color(0xFF3DD68C)
private val Apagado = Color(0xFF8A8F98)
private val Texto = Color(0xFFF2F2F3)

class MainActivity : ComponentActivity() {
    private val pedirVpn = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) Escudo.ligar(this)
    }
    private val pedirNotificacoes = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Estado.carregar(this)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Laranja, background = Fundo, surface = Cartao)) {
                Tela(
                    onLigar = ::ligar,
                    onDesligar = { Escudo.desligar(this) },
                    onNotificacoes = { if (Build.VERSION.SDK_INT >= 33) pedirNotificacoes.launch(Manifest.permission.POST_NOTIFICATIONS) },
                )
            }
        }
    }

    /** Gravado como ligado, mas o escudo não está de pé (o app foi morto sem o Android religar): volta sozinho. */
    override fun onResume() {
        super.onResume()
        if (Estado.ligado.value && !Escudo.rodando && VpnService.prepare(this) == null) Escudo.ligar(this)
    }

    /** A permissão da VPN (o Android pergunta uma vez) e o escudo ligado. */
    private fun ligar() {
        val pergunta = VpnService.prepare(this)
        if (pergunta != null) pedirVpn.launch(pergunta) else Escudo.ligar(this)
    }
}

/** Uma permissão da lista: [ok] ou o botão que leva até ela. */
private class Permissao(val nome: String, val pra: String, val ok: Boolean, val abrir: () -> Unit)

private fun notificacoesOk(ctx: Context) = Build.VERSION.SDK_INT < 33 ||
    ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

private fun bateriaOk(ctx: Context) = ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)

/** O "DNS particular" do Android com um provedor escolhido (dns.google, por exemplo): o escudo não vê os sites. */
private fun dnsParticular(ctx: Context): String? {
    val cm = ctx.getSystemService(ConnectivityManager::class.java)
    return runCatching { cm.getLinkProperties(cm.activeNetwork)?.privateDnsServerName }.getOrNull()
}

@Composable
private fun Tela(onLigar: () -> Unit, onDesligar: () -> Unit, onNotificacoes: () -> Unit) {
    val ctx = LocalContext.current
    val ligado by Estado.ligado.collectAsStateWithLifecycle()
    val rodadas by Estado.rodadas.collectAsStateWithLifecycle()
    var agora by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            agora = System.currentTimeMillis()
            delay(1_000)
        }
    }
    // Volta das Configurações: as permissões são lidas de novo
    var voltas by remember { mutableIntStateOf(0) }
    val dono = LocalLifecycleOwner.current
    DisposableEffect(dono) {
        val olhar = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) voltas++ }
        dono.lifecycle.addObserver(olhar)
        onDispose { dono.lifecycle.removeObserver(olhar) }
    }
    val permissoes = remember(voltas, ligado) {
        listOf(
            Permissao("Usage access", "To know when Instagram or YouTube is open.", Uso.liberado(ctx)) {
                ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            },
            Permissao("Notifications", "For the \"blocked\" notice.", notificacoesOk(ctx), onNotificacoes),
            Permissao("Unrestricted battery", "So Android does not put the shield to sleep.", bateriaOk(ctx)) {
                ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${ctx.packageName}".toUri()))
            },
            Permissao("Local VPN", "Asked when you turn it on. Nothing leaves the phone.", VpnService.prepare(ctx) == null, onLigar),
        )
    }
    val particular = remember(voltas) { dnsParticular(ctx) }
    val bloqueado = rodadas.values.any { it.bloqueada(agora) }

    Column(
        Modifier.fillMaxSize().background(Fundo).safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
    ) {
        Text("Maverick Truck", color = Texto, fontSize = 32.sp, fontWeight = FontWeight.Black)
        Text(
            "Instagram and YouTube, app and site: ${LIVRE_MS / 60_000} minutes of use, then ${BLOQUEIO_MS / 60_000} minutes blocked.",
            color = Apagado, fontSize = 16.sp,
        )
        Spacer(Modifier.height(20.dp))
        for (site in Site.entries) {
            CartaoDoSite(site, rodadas.getValue(site), agora, ligado)
            Spacer(Modifier.height(12.dp))
        }
        Spacer(Modifier.height(8.dp))
        if (!ligado) {
            val pronto = permissoes.first().ok
            Button(
                onClick = onLigar, enabled = pronto, modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Laranja, contentColor = Fundo),
            ) { Text("TURN ON", fontSize = 18.sp, fontWeight = FontWeight.Black) }
            if (!pronto) Text("Allow usage access first.", color = Apagado, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
        } else {
            TextButton(onClick = onDesligar, enabled = !bloqueado, modifier = Modifier.fillMaxWidth()) {
                Text(if (bloqueado) "Turning off waits until nothing is blocked" else "Turn off", fontSize = 15.sp)
            }
        }
        if (particular != null) {
            Spacer(Modifier.height(12.dp))
            Aviso(
                "Private DNS is set to $particular. With it, sites in the browser get through. Set it to Automatic: " +
                    "Settings › Connections › More connection settings › Private DNS.",
            )
        }
        Spacer(Modifier.height(24.dp))
        Text("PERMISSIONS", color = Apagado, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        for (p in permissoes) LinhaDePermissao(p)
        Spacer(Modifier.height(24.dp))
        Text(
            "Counts only while the app (or the site, in a browser) is on screen. Banks and Tourbillon Alarm stay outside " +
                "the shield. Sibling of Maverick, for the Linux desktop.",
            color = Apagado, fontSize = 13.sp,
        )
    }
}

@Composable
private fun CartaoDoSite(site: Site, rodada: Rodada, agora: Long, ligado: Boolean) {
    val bloqueada = rodada.bloqueada(agora)
    val (cor, fracao, centro, linha) = when {
        !ligado -> Quatro(Apagado, 0f, "off", "Shield off")
        bloqueada -> Quatro(
            Vermelho, rodada.restanteMs(agora).toFloat() / BLOQUEIO_MS, mmss(rodada.restanteMs(agora)),
            "Blocked · free again in ${mmss(rodada.restanteMs(agora))}",
        )
        else -> Quatro(
            if (rodada.usadoMs == 0L) Verde else Laranja, rodada.usadoMs.toFloat() / LIVRE_MS, mmss(rodada.restanteMs(agora)),
            "Free · ${mmss(rodada.usadoMs)} of ${mmss(LIVRE_MS)} used",
        )
    }
    Row(
        Modifier.fillMaxWidth().background(Cartao, RoundedCornerShape(20.dp)).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(84.dp), contentAlignment = Alignment.Center) {
            Anel(fracao, cor, Modifier.fillMaxSize())
            Text(centro, color = Texto, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(16.dp))
        Column {
            Text(site.nome, color = Texto, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(linha, color = cor, fontSize = 15.sp)
        }
    }
}

private data class Quatro(val cor: Color, val fracao: Float, val centro: String, val linha: String)

@Composable
private fun Anel(fracao: Float, cor: Color, modifier: Modifier) {
    Canvas(modifier) {
        val traco = 9.dp.toPx()
        val lado = size.minDimension - traco
        val canto = Offset((size.width - lado) / 2, (size.height - lado) / 2)
        drawArc(cor.copy(alpha = 0.18f), 0f, 360f, false, canto, Size(lado, lado), style = Stroke(traco))
        if (fracao > 0f) {
            drawArc(cor, -90f, 360f * fracao.coerceIn(0f, 1f), false, canto, Size(lado, lado), style = Stroke(traco, cap = StrokeCap.Round))
        }
    }
}

@Composable
private fun LinhaDePermissao(p: Permissao) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(p.nome, color = Texto, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(p.pra, color = Apagado, fontSize = 13.sp)
        }
        if (p.ok) {
            Text("✓", color = Verde, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        } else {
            TextButton(onClick = p.abrir) { Text("Allow", color = Laranja, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun Aviso(texto: String) {
    Text(
        texto, color = Texto, fontSize = 14.sp,
        modifier = Modifier.fillMaxWidth().background(Vermelho.copy(alpha = 0.18f), RoundedCornerShape(14.dp)).padding(12.dp),
    )
}
