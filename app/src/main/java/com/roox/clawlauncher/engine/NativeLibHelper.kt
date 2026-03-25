package com.roox.clawlauncher.engine

import android.content.Context
import java.io.File

// At runtime, create a directory with correctly-named symlinks/copies
// of the Termux shared libraries.
//
// Problem: Android only extracts files matching lib*.so from jniLibs.
// Termux Node.js needs versioned names like libz.so.1, libssl.so.3, etc.
// patchelf in CI was unreliable, so instead we fix it at runtime:
//
// 1. All Termux .so files are in nativeLibraryDir as lib[name].so
//    (versioned ones renamed: libz.so.1 → libz_v1.so)
// 2. At runtime, we create a "termux-lib" dir with BOTH names:
//    - Copy libz_v1.so → libz.so.1
//    - Copy libssl_v3.so → libssl.so.3
//    - etc.
// 3. Set LD_LIBRARY_PATH to this directory + nativeLibraryDir
//
// This way the dynamic linker finds everything with original names!
object NativeLibHelper {

    private const val LIB_DIR = "termux-lib"

    fun getLibPath(context: Context): String {
        val libDir = prepareLibs(context)
        val nativeDir = context.applicationInfo.nativeLibraryDir
        return "${libDir.absolutePath}:$nativeDir"
    }

    fun prepareLibs(context: Context): File {
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val libDir = File(context.filesDir, LIB_DIR)

        // Only recreate if needed
        if (libDir.exists() && (libDir.listFiles()?.size ?: 0) > 0) {
            return libDir
        }

        libDir.mkdirs()

        // Go through all .so files in nativeLibraryDir
        nativeDir.listFiles()?.forEach { file ->
            if (!file.name.endsWith(".so")) return@forEach

            val name = file.name
            // Copy with original name
            copyFile(file, File(libDir, name))

            // Also create the versioned name if it was renamed
            // libz_v1.so → also create libz.so.1
            // libssl_v3.so → also create libssl.so.3
            // libcrypto_v3.so → also create libcrypto.so.3
            // libc++_shared_v1.so → also create libc++_shared.so.1 (unlikely but handle)
            val versionedName = revertAndroidName(name)
            if (versionedName != null && versionedName != name) {
                copyFile(file, File(libDir, versionedName))
            }

            // For libs like libc++_shared.so, also ensure bare name exists
            // Some Termux packages reference without version
        }

        return libDir
    }

    // Convert Android-safe name back to original Termux versioned name
    // libz_v1.so → libz.so.1
    // libssl_v3.so → libssl.so.3
    // libcrypto_v3.so → libcrypto.so.3
    // libcares_v2.so → libcares.so.2
    // libicu*.so → no change
    private fun revertAndroidName(androidName: String): String? {
        // Pattern: lib[name]_v[version].so → lib[name].so.[version]
        val regex = Regex("""^(lib.+?)_v(\d+)\.so$""")
        val match = regex.find(androidName) ?: return null
        val baseName = match.groupValues[1]
        val version = match.groupValues[2]
        return "$baseName.so.$version"
    }

    private fun copyFile(src: File, dst: File) {
        try {
            if (dst.exists() && dst.length() == src.length()) return // skip if same size
            src.inputStream().use { input ->
                dst.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        } catch (_: Exception) {
            // Try symlink as fallback
            try {
                if (dst.exists()) dst.delete()
                Runtime.getRuntime().exec(arrayOf("ln", "-sf", src.absolutePath, dst.absolutePath)).waitFor()
            } catch (_: Exception) { }
        }
    }
}
