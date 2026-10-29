package tv.own.owntv.brand

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import tv.own.owntv.core.settings.SettingsRepository
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.random.Random

/**
 * 8K PRO: the DNS servers the app resolves names with (Settings › Network › DNS), chosen on the panel.
 *
 * The panel sends the list with every config answer ("dns": ["185.253.152.214", "85.208.114.51"]); it is
 * kept here so a launch without the panel still has it, with [DEFAULT_SERVERS] before the first answer.
 * The app's DNS setting holds one server, so each time the app is opened the list is tried in order and
 * the first one that answers is the one used. When none answers the setting is left as it is: core falls
 * back to the system DNS when the chosen server fails.
 */
object BrandDns {
    private const val TAG = "BrandDns"
    val DEFAULT_SERVERS = listOf("185.253.152.214", "85.208.114.51")
    private const val PORT = 53
    private const val PROBE_HOST = "google.com"
    private const val TIMEOUT_MS = 2_500
    private const val PREFS = "brand_dns"
    private const val KEY_SERVERS = "servers"

    @Volatile
    private var prefs: android.content.SharedPreferences? = null

    /** Called once at start-up, before anything reads or writes the list. */
    fun init(context: android.content.Context) {
        if (prefs == null) prefs = context.applicationContext.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
    }

    /** The servers to use, in order: the panel's last list, or [DEFAULT_SERVERS]. */
    fun servers(): List<String> =
        prefs?.getString(KEY_SERVERS, null)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?.takeIf { it.isNotEmpty() } ?: DEFAULT_SERVERS

    /** The list the panel sent (IP literals only); an empty or missing list keeps the current one. */
    fun saveServers(list: List<String>) {
        val clean = list.map { it.trim() }.filter { isIpLiteral(it) }.distinct().take(4)
        if (clean.isEmpty()) return
        prefs?.edit()?.putString(KEY_SERVERS, clean.joinToString(","))?.apply()
    }

    /** Turns the brand DNS on with the first server (the one-time default). */
    suspend fun enableDefault(settings: SettingsRepository) {
        settings.saveDns(enabled = true, host = servers().first(), port = PORT, dohUrl = "")
    }

    /**
     * Each time the app is opened: the first server of the list that answers becomes the app's DNS.
     * A DNS-over-HTTPS address typed in by the user is left alone.
     */
    suspend fun apply(settings: SettingsRepository) = withContext(Dispatchers.IO) {
        runCatching {
            val cfg = settings.dnsConfig.first()
            if (cfg.dohUrl.isNotBlank()) return@runCatching
            val chosen = servers().firstOrNull { answers(it) } ?: return@runCatching
            if (cfg.enabled && cfg.host == chosen && cfg.port == PORT) return@runCatching
            Log.i(TAG, "DNS server changed")
            settings.saveDns(enabled = true, host = chosen, port = PORT, dohUrl = "")
        }.onFailure { Log.w(TAG, "DNS check failed: ${it.javaClass.simpleName}") }
    }

    private fun isIpLiteral(text: String): Boolean =
        Regex("^[0-9]{1,3}(\\.[0-9]{1,3}){3}$").matches(text) || (text.contains(':') && Regex("^[0-9a-fA-F:]+$").matches(text))

    /** Whether [server] answers a plain A query for [PROBE_HOST]. */
    private fun answers(server: String): Boolean = runCatching {
        val id = Random.nextInt(0, 0xFFFF)
        val query = buildQuery(id)
        DatagramSocket().use { sock ->
            sock.soTimeout = TIMEOUT_MS
            val address = InetAddress.getByName(server) // a literal IP: no lookup happens
            sock.send(DatagramPacket(query, query.size, address, PORT))
            val buf = ByteArray(512)
            val reply = DatagramPacket(buf, buf.size)
            sock.receive(reply)
            // Same id, "response" bit set, RCODE 0 (no error), at least one answer.
            val sameId = ((buf[0].toInt() and 0xFF) shl 8 or (buf[1].toInt() and 0xFF)) == id
            val isResponse = buf[2].toInt() and 0x80 != 0
            val rcode = buf[3].toInt() and 0x0F
            val answers = (buf[6].toInt() and 0xFF) shl 8 or (buf[7].toInt() and 0xFF)
            sameId && isResponse && rcode == 0 && answers > 0
        }
    }.getOrDefault(false)

    private fun buildQuery(id: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(id shr 8); out.write(id and 0xFF)
        out.write(0x01); out.write(0x00) // recursion desired
        out.write(0); out.write(1) // one question
        repeat(6) { out.write(0) } // no answer, authority or additional records
        PROBE_HOST.split('.').forEach { label ->
            out.write(label.length)
            out.write(label.toByteArray())
        }
        out.write(0)
        out.write(0); out.write(1) // type A
        out.write(0); out.write(1) // class IN
        return out.toByteArray()
    }
}
