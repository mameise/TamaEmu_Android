package com.bernd.tamaemu

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket

/**
 * Besuch zwischen zwei Handys.
 *
 * Der Kern macht das Verbindungsspiel selbst: sobald die Firmware in den
 * Verbindungsmodus geht, verbindet er sich auf 127.0.0.1:PORT (auto_link in
 * periph.c). Diese Bruecke legt nur den Weg ins WLAN - genau wie die
 * Visit-Bridge im Tamago-Projekt. Der Kern bleibt unveraendert, und der
 * Lauscher des Kerns bleibt wie im Original auf 127.0.0.1 beschraenkt.
 *
 *   Gastgeber: ein Lauscher auf allen Schnittstellen. Die erste Verbindung von
 *              aussen ist das andere Handy, die von 127.0.0.1 ist der eigene
 *              Kern. Beide werden zusammengeschaltet.
 *   Gast:      ein Lauscher NUR auf 127.0.0.1 fuer den eigenen Kern, dazu eine
 *              Verbindung nach draussen zum Gastgeber.
 */
object VisitBridge {

    /**
     * Zustand als CODE, nicht als Text - die Oberflaeche macht daraus einen
     * uebersetzten Satz. Frueher stand hier deutscher Klartext, der auch in der
     * englischen Fassung erschien.
     */
    enum class Zustand { AUS, WARTET, KERN_DA, GEGENSTELLE_DA, VERBINDE, LAEUFT, GETRENNT, FEHLER }

    @Volatile var state: Zustand = Zustand.AUS; private set
    /** Beiwerk zum Zustand: Adresse oder Fehlertext. */
    @Volatile var detail: String = ""; private set
    @Volatile var active = false; private set
    var onStatus: (() -> Unit)? = null

    private var srv: ServerSocket? = null
    private var local: Socket? = null
    private var remote: Socket? = null
    private var worker: Thread? = null
    @Volatile private var run = false
    @Volatile var bytesRaus = 0L; private set
    @Volatile var bytesRein = 0L; private set
    private var wifiLock: WifiManager.WifiLock? = null
    private var cpuLock: PowerManager.WakeLock? = null

    private fun say(z: Zustand, d: String = "") {
        state = z
        detail = d
        onStatus?.invoke()
    }

    /** Eigene WLAN-Adresse(n), damit der Gast weiss, wohin er sich verbindet. */
    fun localIps(): String {
        val out = StringBuilder()
        runCatching {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (a in ni.inetAddresses) {
                    val ip = a.hostAddress ?: continue
                    if (a.isLoopbackAddress || ip.contains(':')) continue
                    if (out.isNotEmpty()) out.append(", ")
                    out.append(ip)
                }
            }
        }
        return if (out.isEmpty()) "keine WLAN-Adresse" else out.toString()
    }

    /**
     * WLAN-Stromsparen ist der groesste Zitter-Erzeuger, und das
     * Verbindungsspiel vertraegt kein Zittern (siehe FINDINGS v12). Fuer die
     * Dauer eines Besuchs bleiben WLAN und Rechenwerk deshalb wach.
     */
    private fun holdLocks(ctx: Context) {
        runCatching {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "tamaemu-besuch")
                .also { it.setReferenceCounted(false); it.acquire() }
            val pm = ctx.applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
            cpuLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tamaemu:besuch")
                .also { it.setReferenceCounted(false); it.acquire(30 * 60 * 1000L) }
        }
    }

    private fun releaseLocks() {
        runCatching { wifiLock?.release() }
        runCatching { cpuLock?.release() }
        wifiLock = null; cpuLock = null
    }

    fun startHost(ctx: Context, port: Int) {
        stop()
        holdLocks(ctx)
        run = true
        active = true
        EmuNative.linkAuto(true, port)
        say(Zustand.WARTET, "${localIps()}:$port")
        worker = Thread {
            runCatching {
                val s = ServerSocket()
                s.reuseAddress = true
                s.bind(InetSocketAddress(port))       // alle Schnittstellen
                srv = s
                while (run) {
                    val sock = s.accept()
                    sock.tcpNoDelay = true
                    if (sock.inetAddress?.isLoopbackAddress == true) {
                        local?.runCatching { close() }
                        local = sock
                        say(Zustand.KERN_DA)
                    } else {
                        remote?.runCatching { close() }
                        remote = sock
                        say(Zustand.GEGENSTELLE_DA, sock.inetAddress?.hostAddress ?: "")
                    }
                    pairIfReady()
                }
            }.onFailure { if (run) say(Zustand.FEHLER, it.message ?: "") }
        }.also { it.isDaemon = true; it.start() }
    }

    fun startClient(ctx: Context, host: String, port: Int) {
        stop()
        holdLocks(ctx)
        run = true
        active = true
        EmuNative.linkAuto(true, port)
        say(Zustand.VERBINDE, "$host:$port")
        worker = Thread {
            runCatching {
                val s = ServerSocket()
                s.reuseAddress = true
                s.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port))
                srv = s
                // Erst die Gegenstelle holen, dann auf den eigenen Kern warten.
                val r = Socket()
                r.connect(InetSocketAddress(host, port), 8000)
                r.tcpNoDelay = true
                remote = r
                say(Zustand.GEGENSTELLE_DA, host)
                while (run) {
                    val sock = s.accept()
                    sock.tcpNoDelay = true
                    local?.runCatching { close() }
                    local = sock
                    say(Zustand.KERN_DA)
                    pairIfReady()
                }
            }.onFailure { if (run) say(Zustand.FEHLER, it.message ?: "") }
        }.also { it.isDaemon = true; it.start() }
    }

    private fun pairIfReady() {
        val l = local ?: return
        val r = remote ?: return
        say(Zustand.LAEUFT)
        pipe(l.getInputStream(), r.getOutputStream(), true)
        pipe(r.getInputStream(), l.getOutputStream(), false)
    }

    private fun pipe(from: InputStream, to: OutputStream, hin: Boolean) {
        Thread {
            val buf = ByteArray(512)
            runCatching {
                while (run) {
                    val n = from.read(buf)
                    if (n < 0) break
                    to.write(buf, 0, n)
                    to.flush()
                    if (hin) bytesRaus += n else bytesRein += n
                }
            }
            if (run) say(Zustand.GETRENNT)
        }.also { it.isDaemon = true; it.start() }
    }

    fun stop() {
        releaseLocks()
        run = false
        bytesRaus = 0; bytesRein = 0
        active = false
        runCatching { srv?.close() }
        runCatching { local?.close() }
        runCatching { remote?.close() }
        srv = null; local = null; remote = null
        worker = null
        EmuNative.linkAuto(false, 0)
        say(Zustand.AUS)
    }

}
