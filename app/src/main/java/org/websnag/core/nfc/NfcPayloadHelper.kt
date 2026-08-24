package org.websnag.core.nfc

import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.Tag
import android.nfc.tech.Ndef
import java.nio.charset.StandardCharsets

object NfcPayloadHelper {

    private const val URI_SCHEME = "websnag://tag/"

    /**
     * Converts raw tag byte array UID to uppercase hexadecimal string.
     */
    fun bytesToHex(bytes: ByteArray?): String {
        if (bytes == null) return ""
        val sb = StringBuilder()
        for (b in bytes) {
            sb.append(String.format("%02X", b))
        }
        return sb.toString()
    }

    /**
     * Reads a custom WebSnag URI payload from an NDEF tag if present.
     */
    fun readWebSnagPayload(tag: Tag): String? {
        val ndef = Ndef.get(tag) ?: return null
        return try {
            ndef.connect()
            val ndefMessage = ndef.ndefMessage ?: return null
            for (record in ndefMessage.records) {
                val uri = record.toUri()?.toString()
                if (uri != null && uri.startsWith(URI_SCHEME)) {
                    return uri.removePrefix(URI_SCHEME)
                }
            }
            null
        } catch (e: Exception) {
            null
        } finally {
            try { ndef.close() } catch (_: Exception) {}
        }
    }

    /**
     * Attempts to write a WebSnag NDEF payload to the tag if writable.
     */
    fun writeWebSnagPayload(tag: Tag, tagUuid: String): Boolean {
        val ndef = Ndef.get(tag) ?: return false
        return try {
            ndef.connect()
            if (!ndef.isWritable) return false

            val uriRecord = NdefRecord.createUri("$URI_SCHEME$tagUuid")
            val message = NdefMessage(arrayOf(uriRecord))
            ndef.writeNdefMessage(message)
            true
        } catch (e: Exception) {
            false
        } finally {
            try { ndef.close() } catch (_: Exception) {}
        }
    }
}
