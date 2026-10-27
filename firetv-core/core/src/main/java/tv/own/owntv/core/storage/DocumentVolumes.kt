package tv.own.owntv.core.storage

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * The volume a SAF document or tree actually sits on.
 *
 * The Storage Access Framework reports no free space — there is no "how much room is left" in the
 * contract, because a provider may be a cloud with no answer to give. Both places that need one
 * (the Downloads storage bar, and the recorder's "stop before the card fills" guard) therefore work
 * it out the same way, and this is that one way rather than two.
 *
 * It resolves the system's own `externalstorage` provider, which is where every folder on internal
 * storage or an SD card comes from: its document ids are `primary:Some/Folder` for internal shared
 * storage, or `<volume-uuid>:Some/Folder` for a card. A tree from anywhere else has no volume to
 * measure and gets null, and the caller decides what to say about that.
 */
internal object DocumentVolumes {

    /** The `externalstorage` provider's name for internal shared storage. */
    private const val PRIMARY_VOLUME = "primary"

    /** The volume part of a document id: everything before the first colon. */
    fun volumeIdOf(documentId: String?): String? =
        documentId?.substringBefore(':')?.takeIf { it.isNotBlank() && it != documentId }

    /** The directory backing [volumeId], or null when it is not a local volume at all. */
    fun dirOf(context: Context, volumeId: String?): File? {
        val id = volumeId ?: return null
        if (id.equals(PRIMARY_VOLUME, ignoreCase = true)) {
            return Environment.getExternalStorageDirectory()
        }
        // …/<volume>/Android/data/<pkg>/files — four levels up is the volume root itself, the same
        // derivation StorageAccess.appRoots uses to name a removable card.
        return context.getExternalFilesDirs(null).filterNotNull()
            .mapNotNull { it.parentFile?.parentFile?.parentFile?.parentFile }
            .firstOrNull { it.name == id }
    }
}
