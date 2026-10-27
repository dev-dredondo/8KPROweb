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
 * 8K PRO: the brand's custom DNS servers (Settings › Network › DNS), on by default.
 *
 * The app's DNS setting holds one server. The brand has two, so on every launch the one in use is
 * checked and, when it does not answer and the other one does, the setting is switched to the other.
 * Only while the setting is still one of these two: a DNS the user typed in, or DNS turned off, is
 * never touched.
 */
object BrandDns {
    private const val TAG = "BrandDns"
    val SERVERS = listOf("85.208.114.51", "51.170.52.10")
    private const val PORT = 53
    private const val PROBE_HOST = "google.com"
    private const val TIMEOUT_MS = 2_500

    /** Turns the brand DNS on with the first server (the one-time default). */
    suspend fun enableDefault(settings: SettingsRepository) {
        settings.saveDns(enabled = true, host = SERVERS.first(), port = PORT, dohUrl = "")
    }

    /** On launch: keep the brand DNS on a server that answers. */
    suspend fun ensureWorking(settings: SettingsRepository) = withContext(Dispatchers.IO) {
        runCatching {
            val cfg = settings.dnsConfig.first()
            if (!cfg.enabled || cfg.dohUrl.isNotBlank() || cfg.host !in SERVERS) return@runCatching
            if (answers(cfg.host)) return@runCatching
            val other = SERVERS.firstOrNull { it != cfg.host && answers(it) } ?: return@runCatching
            Log.i(TAG, "DNS server not answering, switching to the other brand server")
            settings.saveDns(enabled = true, host = other, port = PORT, dohUrl = "")
        }.onFailure { Log.w(TAG, "DNS check failed: ${it.javaClass.simpleName}") }
    }

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
