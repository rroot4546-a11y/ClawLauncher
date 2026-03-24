package com.roox.clawlauncher.ui.screens

import android.os.Environment
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roox.clawlauncher.ui.theme.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileManagerScreen(
    initialDir: File? = null,
    hasPermission: Boolean,
    onRequestPermission: () -> Unit,
    onBack: () -> Unit
) {
    val startDir = initialDir ?: Environment.getExternalStorageDirectory()
    var currentDir by remember { mutableStateOf(startDir) }
    var files by remember { mutableStateOf(listFiles(startDir)) }
    var selectedFile by remember { mutableStateOf<File?>(null) }
    var showFileContent by remember { mutableStateOf(false) }
    var fileContent by remember { mutableStateOf("") }

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
            colors = TopAppBarDefaults.topAppBarColors(containerColor = ClawDarkBg)
        )

        if (!hasPermission) {
            // Permission needed
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
                    "ClawLauncher needs access to files so OpenClaw can read and write to your storage.",
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
        } else if (showFileContent && selectedFile != null) {
            // File viewer
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { showFileContent = false }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = null, tint = ClawTextPrimary)
                    }
                    Text(selectedFile!!.name, color = ClawTextPrimary, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                }
                Surface(
                    modifier = Modifier.fillMaxSize().padding(8.dp),
                    shape = RoundedCornerShape(10.dp),
                    color = ClawCardBg
                ) {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(12.dp)) {
                        Text(
                            text = fileContent,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = ClawTextSecondary,
                            lineHeight = 16.sp
                        )
                    }
                }
            }
        } else {
            // File browser
            Column {
                // Current path
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
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Quick nav buttons
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    QuickNavChip("📱 Internal") {
                        currentDir = Environment.getExternalStorageDirectory()
                        files = listFiles(currentDir)
                    }
                    QuickNavChip("📥 Download") {
                        currentDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                        files = listFiles(currentDir)
                    }
                    QuickNavChip("🦀 OpenClaw") {
                        // Navigate to app's openclaw dir
                    }
                }

                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    // Go up
                    if (currentDir.parentFile != null) {
                        item {
                            FileItem(
                                name = "..",
                                isDir = true,
                                size = "",
                                date = "",
                                icon = Icons.Default.ArrowUpward,
                                onClick = {
                                    currentDir = currentDir.parentFile!!
                                    files = listFiles(currentDir)
                                }
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
                                    currentDir = file
                                    files = listFiles(file)
                                } else if (isTextFile(file)) {
                                    selectedFile = file
                                    fileContent = try {
                                        val text = file.readText()
                                        if (text.length > 50000) text.take(50000) + "\n\n... (truncated)" else text
                                    } catch (e: Exception) {
                                        "Error reading file: ${e.message}"
                                    }
                                    showFileContent = true
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun FileItem(
    name: String,
    isDir: Boolean,
    size: String,
    date: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
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
            if (isDir) {
                Icon(Icons.Default.ChevronRight, contentDescription = null, tint = ClawTextSecondary.copy(alpha = 0.3f), modifier = Modifier.size(18.dp))
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

private fun getFileIcon(name: String): androidx.compose.ui.graphics.vector.ImageVector {
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
