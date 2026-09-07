package com.roox.clawlauncher.ui.screens

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.*
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roox.clawlauncher.ui.theme.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FileManagerScreen(
    openclawDir: File? = null,
    hasPermission: Boolean,
    onRequestPermission: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val startDir = Environment.getExternalStorageDirectory()
    var currentDir by remember { mutableStateOf(startDir) }
    var files by remember { mutableStateOf(listFiles(startDir)) }

    // Editor
    var editorTarget by remember { mutableStateOf<File?>(null) }
    var editorText by remember { mutableStateOf("") }

    // Dialog state
    var newFileName by remember { mutableStateOf<String?>(null) }
    var actionTarget by remember { mutableStateOf<File?>(null) }   // file for the action menu
    var renameTarget by remember { mutableStateOf<File?>(null) }
    var renameValue by remember { mutableStateOf("") }
    var confirmDeleteTarget by remember { mutableStateOf<File?>(null) }
    var toastMsg by remember { mutableStateOf<String?>(null) }

    // SAF import picker
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            val ok = copyUriToDir(context, uri, currentDir)
            toastMsg = if (ok) "Imported → ${currentDir.name}" else "Import failed"
            files = listFiles(currentDir)
        }
    }

    // Auto-toast
    toastMsg?.let { msg ->
        LaunchedEffect(msg) {
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            toastMsg = null
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ClawDarkBg)
    ) {
        TopAppBar(
            title = { Text("Files", color = ClawTextPrimary) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = ClawTextPrimary)
                }
            },
            actions = {
                if (openclawDir != null && currentDir.absolutePath != openclawDir.absolutePath) {
                    IconButton(onClick = { navigateTo(context, openclawDir) { currentDir = it; files = listFiles(it) } }) {
                        Icon(Icons.Default.Key, contentDescription = "OpenClaw dir", tint = ClawYellow)
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = ClawDarkBg)
        )

        if (!hasPermission) {
            PermissionGate(onRequestPermission)
        } else if (editorTarget != null) {
            TextEditor(
                file = editorTarget!!,
                initialText = editorText,
                onBack = { editorTarget = null; files = listFiles(currentDir) },
                onSave = { newText ->
                    var errMsg: String? = null
                    val ok = try { editorTarget!!.writeText(newText); true } catch (e: Exception) { errMsg = e.message; false }
                    toastMsg = if (ok) "Saved ✓" else "Save failed: $errMsg"
                    editorTarget = null
                    files = listFiles(currentDir)
                }
            )
        } else {
            BrowserContent(
                currentDir = currentDir,
                files = files,
                openclawDir = openclawDir,
                onNavigate = { dir -> currentDir = dir; files = listFiles(dir) },
                onEdit = { f -> editorTarget = f; editorText = try { f.readText() } catch (e: Exception) { "" } },
                onNew = { newFileName = "" },
                onImport = { importPicker.launch(arrayOf("*/*")) },
                onAction = { file -> actionTarget = file; renameValue = file.name },
                onToast = { toastMsg = it }
            )
        }
    }

    // ── New file dialog ────────────────────────────────────────────────
    newFileName?.let { _ ->
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { newFileName = null },
            containerColor = ClawCardBg,
            title = { Text("Create file", color = ClawTextPrimary) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name e.g. notes.txt", color = ClawTextSecondary) },
                    singleLine = true,
                    textStyle = editorTextStyle()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val n = name.trim()
                    if (n.isNotEmpty() && n.none { it == '/' || it == '\\' }) {
                        var errMsg: String? = null
                        val ok = try { File(currentDir, n).apply { writeText("") }; true } catch (e: Exception) { errMsg = e.message; false }
                        toastMsg = if (ok) "$n created" else "Create failed: $errMsg"
                        files = listFiles(currentDir)
                    }
                    newFileName = null
                }) { Text("Create", color = ClawBlue) }
            },
            dismissButton = {
                TextButton(onClick = { newFileName = null }) { Text("Cancel", color = ClawTextSecondary) }
            }
        )
    }

    // ── File action menu (export / rename / delete) ────────────────────
    actionTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { actionTarget = null },
            containerColor = ClawCardBg,
            title = { Text(target.name, color = ClawTextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {
                Column {
                    ActionRow(Icons.Default.Download, "Export to phone (Download)") {
                        val dest = exportToDownloads(context, target)
                        toastMsg = if (dest != null) "Exported → $dest" else null
                        actionTarget = null
                    }
                    ActionRow(Icons.Default.DriveFileRenameOutline, "Rename") {
                        actionTarget = null
                        renameTarget = target
                        renameValue = target.name
                    }
                    ActionRow(Icons.Default.Delete, "Delete", color = ClawRed) {
                        actionTarget = null
                        confirmDeleteTarget = target
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { actionTarget = null }) { Text("Close", color = ClawBlue) }
            }
        )
    }

    // ── Rename dialog ──────────────────────────────────────────────────
    renameTarget?.let { target ->
        var name by remember { mutableStateOf(renameValue) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            containerColor = ClawCardBg,
            title = { Text("Rename", color = ClawTextPrimary) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("New name", color = ClawTextSecondary) },
                    textStyle = editorTextStyle()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (name.trim().isNotEmpty()) {
                        var errMsg: String? = null
                        val ok = try { target.renameTo(File(target.parent, name.trim())); true } catch (e: Exception) { errMsg = e.message; false }
                        toastMsg = if (ok) "Renamed ✓" else "Rename failed: $errMsg"
                        files = listFiles(currentDir)
                    }
                    renameTarget = null
                }) { Text("Rename", color = ClawBlue) }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("Cancel", color = ClawTextSecondary) }
            }
        )
    }

    // ── Delete confirm ─────────────────────────────────────────────────
    confirmDeleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { confirmDeleteTarget = null },
            containerColor = ClawCardBg,
            title = { Text("Delete?", color = ClawTextPrimary) },
            text = { Text("Delete ${target.name} permanently?", color = ClawTextSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    var errMsg: String? = null
                    val ok = try { target.deleteRecursively(); true } catch (e: Exception) { errMsg = e.message; false }
                    toastMsg = if (ok) "Deleted ✓" else "Delete failed: $errMsg"
                    confirmDeleteTarget = null
                    files = listFiles(currentDir)
                }) { Text("Delete", color = ClawRed) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteTarget = null }) { Text("Cancel", color = ClawTextSecondary) }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BrowserContent(
    currentDir: File,
    files: List<File>,
    openclawDir: File?,
    onNavigate: (File) -> Unit,
    onEdit: (File) -> Unit,
    onNew: () -> Unit,
    onImport: () -> Unit,
    onAction: (File) -> Unit,
    onToast: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Path
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            shape = RoundedCornerShape(8.dp),
            color = ClawCardBg
        ) {
            Row(
                modifier = Modifier.padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Folder, contentDescription = null, tint = ClawYellow, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    currentDir.absolutePath,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = ClawTextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // Actions: New + Import
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.Start
        ) {
            ActionBtn(Icons.Default.Add, "New") { onNew() }
            Spacer(modifier = Modifier.width(6.dp))
            ActionBtn(Icons.Default.AddCircle, "Import") { onImport() }
        }

        // Quick nav
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            QuickNavChip("📱 Internal") {
                onNavigate(Environment.getExternalStorageDirectory())
            }
            QuickNavChip("📥 Download") {
                onNavigate(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS))
            }
            QuickNavChip("🐙 OpenClaw") {
                if (openclawDir != null) onNavigate(openclawDir)
                else onToast("OpenClaw dir not ready")
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            if (currentDir.parentFile != null) {
                item {
                    FileItem(
                        name = "..",
                        isDir = true,
                        size = "",
                        date = "",
                        icon = Icons.Default.ArrowUpward,
                        onClick = { onNavigate(currentDir.parentFile!!) },
                        onAction = null
                    )
                }
            }

            items(files) { file ->
                val sdf = SimpleDateFormat("MMM dd HH:mm", Locale.US)
                FileItem(
                    name = file.name,
                    isDir = file.isDirectory,
                    size = if (file.isFile) formatSize(file.length()) else "${file.listFiles()?.size ?: 0} items",
                    date = sdf.format(Date(file.lastModified())),
                    icon = if (file.isDirectory) Icons.Default.Folder else getFileIcon(file.name),
                    onClick = {
                        if (file.isDirectory) {
                            onNavigate(file)
                        } else if (isTextFile(file)) {
                            onEdit(file)
                        }
                    },
                    onAction = { onAction(file) }
                )
            }
        }
    }
}

@Composable
private fun ActionRow(icon: ImageVector, label: String, color: Color = ClawTextPrimary, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(12.dp))
        Text(label, color = color, fontSize = 15.sp)
    }
}

@Composable
private fun PermissionGate(onRequestPermission: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Default.FolderOff, contentDescription = null, tint = ClawOrange, modifier = Modifier.size(64.dp))
        Spacer(modifier = Modifier.height(16.dp))
        Text("Storage Permission Required", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = ClawTextPrimary)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "ClawLauncher needs file access so you can copy, edit and import OpenClaw files.",
            fontSize = 13.sp, color = ClawTextSecondary, modifier = Modifier.padding(horizontal = 20.dp)
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = onRequestPermission,
            modifier = Modifier.fillMaxWidth(0.7f).height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = ClawRed)
        ) {
            Icon(Icons.Default.Lock, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Grant Permission", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun TextEditor(
    file: File,
    initialText: String,
    onBack: () -> Unit,
    onSave: (String) -> Unit
) {
    var text by remember { mutableStateOf(TextFieldValue(initialText)) }

    Column(modifier = Modifier.fillMaxSize()) {
        Surface(color = ClawDarkBg) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Cancel", tint = ClawTextPrimary)
                }
                Text(
                    file.name,
                    color = ClawTextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                TextButton(onClick = { onSave(text.text) }) {
                    Icon(Icons.Default.Save, contentDescription = null, tint = ClawBlue, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Save", color = ClawBlue, fontWeight = FontWeight.Bold)
                }
            }
        }
        Surface(
            modifier = Modifier.fillMaxSize().padding(8.dp),
            shape = RoundedCornerShape(10.dp),
            color = ClawCardBg
        ) {
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxSize().padding(12.dp),
                textStyle = TextStyle(
                    color = ClawTextPrimary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 16.sp
                ),
                cursorBrush = SolidColor(ClawBlue)
            )
        }
    }
}

@Composable
private fun ActionBtn(icon: ImageVector, label: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        color = ClawCardBg
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = ClawBlue, modifier = Modifier.size(15.dp))
            Spacer(modifier = Modifier.width(5.dp))
            Text(label, fontSize = 12.sp, color = ClawTextPrimary)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileItem(
    name: String,
    isDir: Boolean,
    size: String,
    date: String,
    icon: ImageVector,
    onClick: () -> Unit,
    onAction: (() -> Unit)?
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onAction),
        color = ClawDarkBg
    ) {
        Row(
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon, contentDescription = null,
                tint = if (isDir) ClawYellow else ClawTextSecondary,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(name, fontSize = 14.sp, color = ClawTextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (date.isNotBlank()) {
                    Text("$size  •  $date", fontSize = 11.sp, color = ClawTextSecondary.copy(alpha = 0.6f))
                }
            }
            val action = onAction
            if (action != null) {
                IconButton(onClick = action, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.MoreVert, contentDescription = null, tint = ClawTextSecondary.copy(alpha = 0.6f), modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
fun QuickNavChip(label: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        color = ClawCardBg
    ) {
        Text(label, fontSize = 12.sp, color = ClawTextSecondary, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
    }
}

@Composable
private fun editorTextStyle(): TextStyle =
    MaterialTheme.typography.bodyMedium.copy(color = ClawTextPrimary)

private fun navigateTo(context: Context, dir: File, onNav: (File) -> Unit) {
    if (dir.exists()) onNav(dir)
    else Toast.makeText(context, "Dir not ready", Toast.LENGTH_SHORT).show()
}

/** Copy a file/folder into the phone's public Downloads area; returns dest path or null. */
fun exportToDownloads(context: Context, file: File): String? {
    return try {
        val base = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val dir = File(base, "ClawLauncher")
        if (!dir.exists()) dir.mkdirs()
        val dest = File(dir, file.name)
        if (file.isDirectory) {
            copyDirectory(file, dest)
        } else {
            file.copyTo(dest, overwrite = true)
        }
        dest.absolutePath
    } catch (e: Exception) {
        null
    }
}

private fun copyDirectory(src: File, dst: File) {
    if (src.isDirectory) {
        if (!dst.exists()) dst.mkdirs()
        src.listFiles()?.forEach { child ->
            copyDirectory(child, File(dst, child.name))
        }
    } else {
        src.copyTo(dst, overwrite = true)
    }
}

/** Copy content from a content Uri (SAF picker) into a directory, returning success. */
fun copyUriToDir(context: Context, uri: Uri, destDir: File): Boolean {
    return try {
        val name = queryDisplayName(context, uri) ?: ("imported_" + System.currentTimeMillis())
        val dest = File(destDir, name)
        context.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { input.copyTo(it) }
        } ?: return false
        true
    } catch (e: Exception) {
        false
    }
}

private fun queryDisplayName(context: Context, uri: Uri): String? {
    return try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) cursor.getString(idx) else null
            } else null
        }
    } catch (e: Exception) { null }
}

private fun listFiles(dir: File): List<File> {
    return try {
        (dir.listFiles()?.toList() ?: emptyList())
            .sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
    } catch (_: Exception) {
        emptyList()
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${"%.1f".format(bytes / 1024.0)} KB"
    bytes < 1024 * 1024 * 1024 -> "${"%.1f".format(bytes / (1024.0 * 1024.0))} MB"
    else -> "${"%.2f".format(bytes / (1024.0 * 1024.0 * 1024.0))} GB"
}

private fun isTextFile(file: File): Boolean {
    val ext = file.extension.lowercase()
    return ext in listOf("txt", "md", "json", "xml", "html", "css", "js", "kt", "java", "py",
        "sh", "yaml", "yml", "toml", "cfg", "conf", "ini", "log", "csv", "env", "properties",
        "gradle", "pro", "gitignore", "editorconfig", "ts", "tsx", "jsx", "vue", "svg")
}

private fun getFileIcon(name: String): ImageVector {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "jpg", "jpeg", "png", "gif", "webp", "bmp" -> Icons.Default.Image
        "mp4", "mkv", "avi", "mov", "webm" -> Icons.Default.PlayCircle
        "mp3", "wav", "ogg", "flac", "aac", "m4a" -> Icons.Default.MusicNote
        "pdf" -> Icons.Default.PictureAsPdf
        "apk" -> Icons.Default.Android
        "zip", "tar", "gz", "rar", "7z" -> Icons.Default.FolderZip
        "json", "xml", "yaml", "yml" -> Icons.Default.DataObject
        "kt", "java", "py", "js", "ts" -> Icons.Default.Code
        else -> Icons.Default.InsertDriveFile
    }
}
