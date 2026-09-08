package com.piandroid

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val termuxRunCommandPermission = "com.termux.permission.RUN_COMMAND"
    private val termuxPermissionRequestCode = 7001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestTermuxPermissionIfNeeded()
        setContent { PiScreen(PiBridge(this)) }
    }

    private fun requestTermuxPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val termuxInstalled = runCatching {
            packageManager.getPackageInfo("com.termux", 0)
        }.isSuccess
        if (termuxInstalled && checkSelfPermission(termuxRunCommandPermission) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(termuxRunCommandPermission), termuxPermissionRequestCode)
        }
    }
}

@Composable
private fun PiScreen(bridge: PiBridge) {
    var cwd by remember { mutableStateOf("/data/data/com.termux/files/home/project") }
    var input by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("未连接") }
    var output by remember { mutableStateOf(listOf<String>()) }
    var cursor by remember { mutableLongStateOf(0L) }
    var view by remember { mutableStateOf("chat") }
    var currentPath by remember { mutableStateOf("") }
    var files by remember { mutableStateOf(listOf<PiFile>()) }
    var selectedFile by remember { mutableStateOf("") }
    var fileText by remember { mutableStateOf("") }
    var diffText by remember { mutableStateOf("还没有加载 diff") }
    var terminalInput by remember { mutableStateOf("") }
    var terminalOutput by remember { mutableStateOf(listOf<String>()) }
    val slashCommands = remember {
        listOf(
            "/help" to "查看可用命令",
            "/tree" to "打开项目文件树",
            "/diff" to "查看当前改动",
            "/model" to "切换模型",
            "/thinking" to "设置思考等级",
            "/settings" to "打开 Pi 设置"
        )
    }
    val scope = rememberCoroutineScope()
    LaunchedEffect(status) {
        while (status == "运行中") {
            bridge.events(cursor).onSuccess { batch ->
                output = output + batch.events.mapNotNull { event -> event.text.takeIf { it.isNotBlank() } }
                terminalOutput = terminalOutput + batch.events.filter { it.type.startsWith("terminal_") }.mapNotNull { it.text.takeIf { text -> text.isNotBlank() } }
                cursor = batch.latest
            }
            delay(500)
        }
    }
    MaterialTheme { Surface(Modifier.fillMaxSize()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Pi Android", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            Button(onClick = { view = "chat" }) { Text("对话") }
            Button(onClick = { view = "terminal" }) { Text("终端") }
        }
        Text(status)
        OutlinedTextField(cwd, { cwd = it }, Modifier.fillMaxWidth(), label = { Text("Termux 项目目录") })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { scope.launch {
                status = "正在启动 Termux bridge"
                bridge.installAndStartBridge(cwd).fold(
                    onSuccess = { bridge.start(cwd).onSuccess { status = "运行中" }.onFailure { status = it.message ?: "Pi 启动失败" } },
                    onFailure = { status = it.message ?: "启动失败" }
                )
            } }) { Text("连接 Pi") }
            Button(onClick = { scope.launch { bridge.start(cwd).onSuccess { status = "运行中" }.onFailure { status = it.message ?: "启动失败" } } }) { Text("启动") }
        }
        when (view) {
            "chat" -> LazyColumn(Modifier.weight(1f)) { items(output) { Text(it, Modifier.padding(vertical = 4.dp)) } }
            "files" -> Column(Modifier.weight(1f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { scope.launch { bridge.files(currentPath.substringBeforeLast('/', "")).onSuccess { files = it; currentPath = currentPath.substringBeforeLast('/', "") } } }) { Text("上级") }
                    Text(if (currentPath.isBlank()) "/" else currentPath, Modifier.padding(top = 12.dp))
                }
                LazyColumn(Modifier.weight(1f)) { items(files) { item ->
                    Text(
                        (if (item.type == "directory") "[DIR] " else "[FILE] ") + item.name,
                        Modifier.fillMaxWidth().clickable {
                            if (item.type == "directory") scope.launch { bridge.files(item.path).onSuccess { files = it; currentPath = item.path } }
                            else { selectedFile = item.path; scope.launch { bridge.file(item.path).onSuccess { fileText = it } } }
                        }.padding(vertical = 9.dp)
                    )
                } }
                if (selectedFile.isNotBlank()) {
                    Text(selectedFile, Modifier.padding(top = 10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { scope.launch { bridge.writeFile(selectedFile, fileText).onSuccess { output = output + "已保存：$selectedFile"; bridge.diff().onSuccess { diffText = it } } } }) { Text("保存") }
                        Button(onClick = { scope.launch { bridge.file(selectedFile).onSuccess { fileText = it } } }) { Text("重新读取") }
                    }
                    OutlinedTextField(
                        value = fileText,
                        onValueChange = { fileText = it },
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        label = { Text("文件内容") }
                    )
                }
            }
            "diff" -> Text(diffText, Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()))
            "terminal" -> LazyColumn(Modifier.weight(1f)) { items(terminalOutput) { Text(it, Modifier.padding(vertical = 4.dp)) } }
        }
        if (view != "terminal" && input.startsWith("/")) {
            val query = input.substringBefore(" ").lowercase()
            Column(
                Modifier.fillMaxWidth().background(Color(0xFF1A1F2A), RoundedCornerShape(12.dp)).padding(vertical = 4.dp)
            ) {
                slashCommands.filter { it.first.startsWith(query) }.forEach { (command, description) ->
                    Row(
                        Modifier.fillMaxWidth().clickable { input = "$command " }.padding(horizontal = 12.dp, vertical = 9.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(command, fontSize = 13.sp)
                        Text(description, color = Color(0xFF9BA6B8), fontSize = 12.sp)
                    }
                }
            }
        }
        if (view == "terminal") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(terminalInput, { terminalInput = it }, Modifier.weight(1f), label = { Text("输入终端命令") })
                Button(onClick = { val command = terminalInput.trim(); if (command.isNotEmpty()) { terminalInput = ""; scope.launch { bridge.terminal(command) } } }) { Text("执行") }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(input, { input = it }, Modifier.weight(1f), label = { Text("给 Pi 的指令") })
                Button(onClick = {
                    val message = input.trim()
                    if (message.isNotEmpty()) {
                        input = ""
                        output = output + "你：$message"
                        scope.launch {
                            when (message.substringBefore(' ')) {
                                "/tree" -> bridge.files().onSuccess { files = it; currentPath = ""; view = "files" }
                                "/diff" -> bridge.diff().onSuccess { diffText = it; view = "diff" }
                                "/help" -> output = output + "可用命令：/tree /diff /model /thinking /settings"
                                else -> bridge.prompt(message)
                            }
                        }
                    }
                }) { Text("发送") }
            }
        }
    } } }
}
