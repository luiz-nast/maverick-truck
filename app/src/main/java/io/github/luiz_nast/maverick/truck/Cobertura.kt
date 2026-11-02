// A tela que aparece por cima do app bloqueado (Instagram ou YouTube) quando ele está na frente, pra tirar a pessoa de
// lá de verdade, não só cortar a internet por baixo. É o que o Maverick do computador faz (a "Cover"). Usa a permissão
// "Aparecer sobre outros apps" (SYSTEM_ALERT_WINDOW), que é diferente de acessibilidade: o Nubank não implica com ela.
//
// No fundo, um carrossel: os carros muito luxuosos (CARROS) e as fotos que o dono pôs da galeria (Fotos), só a imagem,
// sem texto embaixo. Por cima, o aviso e o botão que leva pra tela inicial. Enquanto ela está na tela, toca uma música
// aleatória da biblioteca dividida com o Tourbillon Alarm (Musicas), começando em 35% do volume de mídia, e outra
// quando acaba; o volume de antes volta quando a tela some, se a pessoa não mexeu (o dono, 10-31).
//
// Só aparece sobre o app do site bloqueado (o escudo só manda mostrar quando esse app está na frente); nunca sobre um
// banco, o despertador ou qualquer outro. Sem a permissão, não faz nada.
package io.github.luiz_nast.maverick.truck

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import androidx.core.net.toUri
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.concurrent.thread

class Cobertura(private val ctx: Context, private val aoUsarNavegador: () -> Unit = {}) {
    private val janelas = ctx.getSystemService(WindowManager::class.java)
    private val audio = ctx.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var vista: View? = null
    private var foto: ImageView? = null
    private var titulo: TextView? = null
    private var linha: TextView? = null
    private var siteNaTela: Site? = null
    private var botaoNavegador: Button? = null
    private var navegadorNaTela: String? = null // o navegador na frente, se o site está nele (não no app)

    /** As fotos desta vez (carros e galeria, embaralhadas) e a que está na tela. */
    private var fotos: List<Any> = emptyList()
    private var indice = 0

    private var tocador: MediaPlayer? = null
    private var somAtual: String? = null
    private var vez = 0 // cada vez que a tela aparece; uma música que chega atrasada de outra vez é jogada fora
    private var erros = 0
    private var volumeAntes: Int? = null // o volume de mídia de antes da música, pra devolver
    private var volumeAlvo: Int? = null // o que a música pôs: se a pessoa mexeu depois, o dela fica
    private var foco: AudioFocusRequest? = null

    /** A próxima foto do carrossel, a cada [TROCA_MS], com um esmaecer rápido. */
    private val trocar = object : Runnable {
        override fun run() {
            val f = foto ?: return
            if (fotos.size > 1) {
                f.animate().alpha(0f).setDuration(FADE_MS).withEndAction {
                    indice = (indice + 1) % fotos.size
                    mostrarFoto()
                    f.animate().alpha(1f).setDuration(FADE_MS).start()
                }.start()
            }
            handler.postDelayed(this, TROCA_MS)
        }
    }

    /** Mostra (ou atualiza) a tela por cima pro [site] bloqueado até [ate] (epoch, ms). Sem a permissão, não faz nada. */
    fun mostrar(site: Site, ate: Long, navegador: String? = null) {
        if (Build.VERSION.SDK_INT < 23 || !Settings.canDrawOverlays(ctx)) return
        if (vista == null) {
            criar()
            if (vista == null) return
            handler.postDelayed(trocar, TROCA_MS)
            tocarMusica()
        }
        navegadorNaTela = navegador
        botaoNavegador?.visibility = if (navegador != null) View.VISIBLE else View.GONE
        if (site != siteNaTela) {
            siteNaTela = site
            titulo?.text = "${site.nome} is blocked"
        }
        val hora = DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(ate).atZone(ZoneId.systemDefault()))
        linha?.text = "Your ${LIVRE_MS / 60_000} minutes are up.\nFree again at $hora."
    }

    /** Tira a tela, se estiver aparecendo; a música para e o volume de antes volta. */
    fun esconder() {
        val v = vista ?: return
        vista = null
        foto = null
        titulo = null
        linha = null
        siteNaTela = null
        botaoNavegador = null
        navegadorNaTela = null
        handler.removeCallbacks(trocar)
        pararMusica()
        runCatching { janelas.removeView(v) }
    }

    /** O escudo acabou: tira a tela (e com ela a música). */
    fun liberar() = esconder()

    private fun mostrarFoto() {
        val f = foto ?: return
        when (val atual = fotos.getOrNull(indice)) {
            is Int -> f.setImageResource(atual)
            is File -> {
                val bitmap = runCatching { BitmapFactory.decodeFile(atual.path) }.getOrNull()
                if (bitmap != null) f.setImageBitmap(bitmap) else f.setImageDrawable(null)
            }
        }
    }

    private fun criar() {
        Fotos.carregar(ctx)
        fotos = (CARROS.map { it.desenho } + Fotos.lista.value).shuffled()
        indice = 0
        val raiz = FrameLayout(ctx).apply {
            setBackgroundColor(Color.BLACK)
            isClickable = true // consome os toques: nada passa pro app atrás
        }
        foto = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER } // a foto inteira, não um pedaço ampliado
        raiz.addView(foto, FrameLayout.LayoutParams(-1, -1))
        // Escurece a foto, mais embaixo e no meio, pro texto ler bem por cima de qualquer imagem
        raiz.addView(
            View(ctx).apply {
                background = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(Color.parseColor("#66000000"), Color.parseColor("#AA000000"), Color.parseColor("#DD000000")),
                )
            },
            FrameLayout.LayoutParams(-1, -1),
        )
        titulo = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
            setTextColor(Color.parseColor("#FFECEEF0"))
            gravity = Gravity.CENTER
            setShadowLayer(8f, 0f, 2f, Color.BLACK)
        }
        linha = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(Color.parseColor("#FF949AA0"))
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(32))
            setShadowLayer(8f, 0f, 2f, Color.BLACK)
        }
        val botao = Button(ctx).apply {
            text = "Leave"
            setTextColor(Color.parseColor("#FFECEEF0"))
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(Color.parseColor("#FF8F2E1D"))
            }
            setPadding(dp(48), dp(14), dp(48), dp(14))
            setOnClickListener { paraOInicio() }
        }
        // Só no navegador: usar o navegador pra outra coisa (o dono, 10-31: sem isto, depois do limite era difícil usar o Chrome)
        botaoNavegador = Button(ctx).apply {
            text = "Use the browser for something else"
            isAllCaps = false
            setTextColor(Color.parseColor("#FFECEEF0"))
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(Color.TRANSPARENT)
                setStroke(dp(1), Color.parseColor("#997B848C"))
            }
            setPadding(dp(32), dp(10), dp(32), dp(10))
            visibility = View.GONE
            setOnClickListener { usarONavegador() }
        }
        val meio = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(32), dp(32), dp(32))
            addView(titulo)
            addView(linha)
            addView(botao)
            addView(botaoNavegador, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(16) })
        }
        raiz.addView(meio, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        mostrarFoto()
        val tipo = if (Build.VERSION.SDK_INT >= 26) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            tipo,
            // Não rouba o foco do teclado, mas recebe os toques (o fundo os consome); por cima das barras do sistema
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.OPAQUE,
        )
        runCatching { janelas.addView(raiz, params) }
            .onSuccess { vista = raiz }
            .onFailure { Log.w(TAG, "Tela de bloqueio: o Android não deixou mostrar", it) }
    }

    /**
     * Começa a música: pega o foco de áudio (o app de trás pausa), põe o volume de mídia em 35% e toca um som aleatório
     * da biblioteca. Numa ligação (sem o foco), nada de música nem de volume mexido. A lista vem do Tourbillon, que pode
     * estar dormindo: é lida fora da linha principal.
     */
    private fun tocarMusica() {
        val estaVez = ++vez
        erros = 0
        thread(name = "musica") {
            val nomes = Musicas.listar(ctx).orEmpty()
            handler.post { if (estaVez == vez && vista != null) comecar(nomes) }
        }
    }

    private fun comecar(nomes: List<String>) {
        if (nomes.isEmpty()) {
            Log.i(TAG, "Tela de bloqueio: biblioteca de sons vazia (ou sem o Tourbillon), sem música")
            return
        }
        // Uma ligação ou um alarme tomando o som: a música pausa; perdeu de vez, para
        val pedido = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(atributos())
            .setOnAudioFocusChangeListener({ mudanca ->
                when (mudanca) {
                    AudioManager.AUDIOFOCUS_LOSS -> pararMusica()
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK ->
                        runCatching { tocador?.pause() }
                    AudioManager.AUDIOFOCUS_GAIN -> runCatching { tocador?.start() }
                }
            }, handler)
            .build()
        if (audio.requestAudioFocus(pedido) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Log.i(TAG, "Tela de bloqueio: sem o foco de áudio (ligação?), sem música")
            return
        }
        foco = pedido
        val atual = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val alvo = volumeDaMusica(audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
        if (alvo != atual) {
            volumeAntes = atual
            volumeAlvo = alvo
            runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, alvo, 0) }
        }
        tocarProximo(nomes)
    }

    /** Toca um som sorteado; quando acaba, outro. Um som que não abre conta como erro; 3 seguidos, a música para. */
    private fun tocarProximo(nomes: List<String>) {
        if (vista == null || foco == null) return
        if (erros >= 3) {
            Log.w(TAG, "Tela de bloqueio: 3 sons seguidos não tocaram, sem música desta vez")
            return
        }
        val nome = sortearSom(nomes, somAtual) ?: return
        somAtual = nome
        tocador?.release()
        tocador = null
        val estaVez = vez
        thread(name = "musica") {
            val arquivo = Musicas.abrir(ctx, nome)
            handler.post {
                if (estaVez != vez || vista == null) {
                    arquivo?.close()
                    return@post
                }
                if (arquivo == null) {
                    erros++
                    tocarProximo(nomes)
                    return@post
                }
                val mp = MediaPlayer()
                val tocou = runCatching {
                    arquivo.use { mp.setDataSource(it.fileDescriptor) }
                    mp.setAudioAttributes(atributos())
                    mp.setOnCompletionListener {
                        // Acabou quase na hora (arquivo quebrado): conta como erro, pra não girar sem parar
                        if (it.currentPosition < 2_000) erros++ else erros = 0
                        handler.postDelayed({ if (tocador === it) tocarProximo(nomes) }, 500)
                    }
                    mp.setOnErrorListener { _, _, _ ->
                        erros++
                        tocarProximo(nomes)
                        true
                    }
                    mp.prepare()
                    mp.start()
                }.onFailure { Log.w(TAG, "Tela de bloqueio: $nome não tocou", it) }.isSuccess
                if (tocou) {
                    tocador = mp
                    Log.i(TAG, "Tela de bloqueio: tocando $nome")
                } else {
                    mp.release()
                    erros++
                    tocarProximo(nomes)
                }
            }
        }
    }

    /** A música para (a tela sumiu) e o volume e o foco de áudio voltam como estavam. */
    private fun pararMusica() {
        vez++
        tocador?.let { runCatching { it.stop() }; it.release() }
        tocador = null
        somAtual = null
        val antes = volumeAntes
        // Só se o volume ainda é o que a música pôs: se a pessoa mexeu (ou trocou pro fone), o dela fica
        if (antes != null && audio.getStreamVolume(AudioManager.STREAM_MUSIC) == volumeAlvo) {
            runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, antes, 0) }
        }
        volumeAntes = null
        volumeAlvo = null
        foco?.let { audio.abandonAudioFocusRequest(it) }
        foco = null
    }

    private fun atributos(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    /** Vai pra tela inicial (tira o app bloqueado da frente). A tela por cima some no próximo tique, porque o app saiu da frente. */
    private fun paraOInicio() {
        val inicio = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(inicio) }
    }

    /**
     * Abre uma página em branco do Google no navegador da frente (uma aba nova) e deixa de contar o site que estava
     * nele: a tela some, e a pessoa usa o navegador à vontade. Voltar pra aba do site bloqueado traz a tela de novo
     * (o site é pedido outra vez). Um vídeo que siga tocando em outra aba pode trazer a tela de volta: feche essa aba.
     */
    private fun usarONavegador() {
        val pacote = navegadorNaTela ?: return
        aoUsarNavegador()
        val pagina = Intent(Intent.ACTION_VIEW, "https://www.google.com".toUri())
            .setPackage(pacote)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(pagina) }.onFailure { Log.w(TAG, "Tela de bloqueio: o navegador não abriu a página nova", it) }
        esconder()
    }

    private fun dp(v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    private companion object {
        /** De quanto em quanto tempo o carrossel troca de foto, e o esmaecer da troca. */
        const val TROCA_MS = 5_000L
        const val FADE_MS = 350L
    }
}
