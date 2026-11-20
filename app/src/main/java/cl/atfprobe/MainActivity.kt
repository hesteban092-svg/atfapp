package cl.atfprobe

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView

@SuppressLint("MissingPermission")
class MainActivity : Activity() {
    private val elm = Elm()
    private var devices: List<BluetoothDevice> = emptyList()

    private lateinit var spinner: Spinner
    private lateinit var header: EditText
    private lateinit var startEt: EditText
    private lateinit var endEt: EditText
    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView

    @Volatile private var stop = false
    @Volatile private var busy = false
    private var snapA: Map<Int, String>? = null
    private var snapB: Map<Int, String>? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }

        spinner = Spinner(this)
        root.addView(spinner)

        header = EditText(this).apply { setText("7E1"); hint = "Cabecera TCM"; setSingleLine() }
        root.addView(label("Cabecera del módulo (TCM = 7E1):"))
        root.addView(header)

        root.addView(row(
            btn("Conectar") { connect() },
            btn("Probar TCM") { probe() }
        ))

        startEt = EditText(this).apply { setText("0000"); setSingleLine(); inputType = InputType.TYPE_CLASS_TEXT }
        endEt = EditText(this).apply { setText("03FF"); setSingleLine(); inputType = InputType.TYPE_CLASS_TEXT }
        root.addView(label("Rango de identificadores (hex) a escanear:"))
        root.addView(row(startEt, endEt))

        root.addView(row(
            btn("Captura A (frío)") { scan(0) },
            btn("Captura B (caliente)") { scan(1) }
        ))
        root.addView(row(
            btn("Comparar A vs B") { compare() },
            btn("Detener") { stop = true }
        ))

        logView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextIsSelectable(true)
        }
        scroll = ScrollView(this).apply { addView(logView) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(btn("Compartir log") { share() })

        setContentView(root)

        log("1) Empareja el ELM327 en Ajustes > Bluetooth (clave 1234 o 0000).\n" +
            "2) Elige el dispositivo y pulsa Conectar.\n" +
            "3) Auto estacionado en P, freno de mano, encendido ON.\n" +
            "4) Probar TCM -> mira si responde.\n" +
            "5) Captura A con el auto frío, Captura B con la caja caliente, y Comparar.\n" +
            "Solo lectura: no se escribe nada en el auto.")

        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1)
        } else {
            loadDevices()
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        loadDevices()
    }

    override fun onDestroy() {
        stop = true
        elm.close()
        super.onDestroy()
    }

    private fun loadDevices() {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        devices = adapter?.bondedDevices?.toList() ?: emptyList()
        val names = devices.map { "${it.name} (${it.address})" }
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        val i = devices.indexOfFirst { (it.name ?: "").contains("OBD", ignoreCase = true) }
        if (i >= 0) spinner.setSelection(i)
        if (devices.isEmpty()) log("No hay dispositivos emparejados.")
    }

    private fun label(t: String) = TextView(this).apply { text = t; textSize = 12f }

    private fun btn(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun row(vararg v: android.view.View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        v.forEach { addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
    }

    private fun log(s: String) = runOnUiThread {
        logView.append(s + "\n")
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun work(block: () -> Unit) {
        if (busy) { log("Ocupado. Espera o pulsa Detener."); return }
        busy = true
        stop = false
        Thread {
            try { block() } catch (e: Exception) { log("ERROR: ${e.message}") } finally { busy = false }
        }.start()
    }

    private fun connect() = work {
        val d = devices.getOrNull(spinner.selectedItemPosition)
        if (d == null) { log("Sin dispositivo seleccionado."); return@work }
        log("Conectando a ${d.name}...")
        elm.close()
        elm.connect(d)
        log(elm.init(header.text.toString().trim().uppercase()))
        log("Listo.")
    }

    private fun probe() = work {
        for (req in listOf("22F190", "22F187", "22F18C", "22F194", "0100")) {
            val raw = elm.send(req, 1500)
            log("$req -> ${raw.replace("\n", " | ")}")
        }
        log("Si todo da NO DATA, la TCM no habla UDS estándar en esa cabecera.")
    }

    private fun scan(slot: Int) = work {
        val a = startEt.text.toString().trim().toInt(16)
        val b = endEt.text.toString().trim().toInt(16)
        val found = LinkedHashMap<Int, String>()
        log("Escaneando %04X a %04X...".format(a, b))
        for (did in a..b) {
            if (stop) { log("Detenido en %04X".format(did)); break }
            val hex = Elm.parse(elm.send("22%04X".format(did), 1000))
            val tag = "62%04X".format(did)
            if (hex != null && hex.startsWith(tag)) {
                val data = hex.substring(6)
                found[did] = data
                log("%04X = %s".format(did, data))
            }
            if ((did - a) % 256 == 255) log("... %04X".format(did))
        }
        if (slot == 0) snapA = found else snapB = found
        log("Captura ${if (slot == 0) "A" else "B"}: ${found.size} identificadores respondieron.")
    }

    private fun compare() {
        val a = snapA
        val b = snapB
        if (a == null || b == null) { log("Falta la captura A o la B."); return }
        var n = 0
        for ((did, da) in a) {
            val db = b[did] ?: continue
            if (da == db) continue
            n++
            var line = "%04X: %s -> %s".format(did, da, db)
            if (da.length == 2 && db.length == 2) {
                val x = da.toInt(16)
                val y = db.toInt(16)
                line += "  (%d->%d; A-40: %d->%d C)".format(x, y, x - 40, y - 40)
                if (y - x in 8..80 && x - 40 in -30..60 && y - 40 in 0..140) line += "  *CANDIDATO*"
            } else if (da.length == 4 && db.length == 4) {
                line += "  (%d->%d)".format(da.toInt(16), db.toInt(16))
            }
            log(line)
        }
        log("$n identificadores cambiaron.")
    }

    private fun share() {
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, logView.text.toString())
        }
        startActivity(Intent.createChooser(i, "Compartir log"))
    }
}
