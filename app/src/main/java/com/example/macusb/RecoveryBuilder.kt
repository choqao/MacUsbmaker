package com.example.macusb

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.IOException
import java.util.zip.ZipInputStream

/**
 * No-root path: the drive must already be FAT32 (and GPT/MBR as you like).
 * Pick the drive's root with ACTION_OPEN_DOCUMENT_TREE.
 */
class RecoveryBuilder(private val ctx: Context, private val client: AppleRecoveryClient = AppleRecoveryClient()) {

    fun build(
        tree: Uri,
        target: MacOsTarget,
        efiZip: Uri?,
        log: (String) -> Unit,
        progress: (Float) -> Unit
    ) {
        val root = DocumentFile.fromTreeUri(ctx, tree) ?: throw IOException("Cannot open selected folder")
        val dir = root.findFile("com.apple.recovery.boot")?.takeIf { it.isDirectory }
            ?: root.createDirectory("com.apple.recovery.boot")
            ?: throw IOException("Cannot create com.apple.recovery.boot")

        log("Contacting Apple (${target.name}, ${target.boardId})...")
        val asset = client.fetchAsset(target)

        log("Downloading BaseSystem.chunklist...")
        copyDownload(dir, "BaseSystem.chunklist", asset.chunklistUrl, asset.chunklistToken) { }

        log("Downloading BaseSystem.dmg (this is large)...")
        copyDownload(dir, "BaseSystem.dmg", asset.dmgUrl, asset.dmgToken) { d, t ->
            if (t > 0) progress(d.toFloat() / t)
        }

        if (efiZip != null) {
            log("Extracting EFI folder...")
            extractEfi(root, efiZip, log)
        } else {
            log("No EFI zip chosen - copy your OpenCore EFI folder to the drive root yourself.")
        }
        log("Done. Eject the drive from Android before unplugging.")
    }

    private fun copyDownload(
        dir: DocumentFile, name: String, url: String, token: String, onProgress: (Long, Long) -> Unit
    ) {
        dir.findFile(name)?.delete()
        val file = dir.createFile("application/octet-stream", name)
            ?: throw IOException("Cannot create $name")
        val out = ctx.contentResolver.openOutputStream(file.uri, "w")
            ?: throw IOException("Cannot write $name")
        out.use { client.download(url, token, it, onProgress) }
    }

    private fun extractEfi(root: DocumentFile, zip: Uri, log: (String) -> Unit) {
        val input = ctx.contentResolver.openInputStream(zip) ?: throw IOException("Cannot read EFI zip")
        var count = 0
        ZipInputStream(input.buffered()).use { zis ->
            while (true) {
                val e = zis.nextEntry ?: break
                val name = e.name.replace('\\', '/')
                if (name.startsWith("__MACOSX") || name.contains("/._")) continue
                val idx = if (name.startsWith("EFI/")) 0 else name.indexOf("/EFI/").let { if (it >= 0) it + 1 else -1 }
                if (idx < 0) continue
                val parts = name.substring(idx).split('/').filter { it.isNotEmpty() }
                if (parts.isEmpty()) continue

                var cur = root
                val dirParts = if (e.isDirectory) parts else parts.dropLast(1)
                for (p in dirParts) {
                    cur = cur.findFile(p)?.takeIf { it.isDirectory } ?: cur.createDirectory(p)
                        ?: throw IOException("Cannot create folder $p")
                }
                if (e.isDirectory) continue

                val fname = parts.last()
                cur.findFile(fname)?.delete()
                val f = cur.createFile("application/octet-stream", fname) ?: throw IOException("Cannot create $fname")
                ctx.contentResolver.openOutputStream(f.uri, "w")?.use { zis.copyTo(it) }
                count++
            }
        }
        log("Copied $count EFI files.")
    }
}
