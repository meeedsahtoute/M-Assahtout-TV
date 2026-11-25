package com.streamtube.tv.server

import com.streamtube.tv.core.NativeBridge
import com.streamtube.tv.core.ResolvedUrlDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

class CompanionServer(
    val port: Int = 8787,
    private val onOpenRoute: (ResolvedUrlDto) -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        scope.launch {
            try {
                val ss = ServerSocket(port)
                serverSocket = ss
                while (running.get()) {
                    val client = ss.accept()
                    launch { handleClient(client) }
                }
            } catch (_: Throwable) {
                running.set(false)
            }
        }
    }

    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    fun getLanUrl(): String {
        val ip = findLanIpv4() ?: "127.0.0.1"
        return "http://$ip:$port"
    }

    private fun findLanIpv4(): String? {
        return runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces().toList()
            val addrs = interfaces
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .map { it.hostAddress }
            addrs.firstOrNull { it.startsWith("192.168.") || it.startsWith("10.") || it.startsWith("172.") }
                ?: addrs.firstOrNull()
        }.getOrNull()
    }

    private suspend fun handleClient(socket: Socket) {
        socket.use { s ->
            s.soTimeout = 10_000
            val input = BufferedInputStream(s.getInputStream())
            val output = s.getOutputStream()

            val headerBytes = ByteArrayOutputStream()
            var prev4 = 0
            while (true) {
                val b = input.read()
                if (b == -1) return
                headerBytes.write(b)
                prev4 = (prev4 shl 8) or (b and 0xFF)
                if (prev4 == 0x0D0A0D0A || headerBytes.size() > 64 * 1024) break
            }
            val headerText = headerBytes.toString(StandardCharsets.UTF_8.name())
            val lines = headerText.split("\r\n")
            val reqLine = lines.firstOrNull()?.split(" ") ?: return
            val method = reqLine.getOrNull(0) ?: "GET"
            val path = reqLine.getOrNull(1)?.substringBefore("?") ?: "/"

            var contentLength = 0
            var contentType = ""
            for (line in lines.drop(1)) {
                val idx = line.indexOf(':')
                if (idx > 0) {
                    val k = line.substring(0, idx).trim().lowercase()
                    val v = line.substring(idx + 1).trim()
                    if (k == "content-length") contentLength = v.toIntOrNull() ?: 0
                    if (k == "content-type") contentType = v
                }
            }

            val maxBody = 16 * 1024 * 1024
            val bodyBytes = if (contentLength in 1..maxBody) {
                val buf = ByteArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val n = input.read(buf, read, contentLength - read)
                    if (n <= 0) break
                    read += n
                }
                buf.copyOf(read)
            } else {
                ByteArray(0)
            }

            when {
                method == "GET" && path == "/" -> writeHtml(output, INDEX_HTML)
                method == "GET" && path == "/api/status" -> {
                    val auth = NativeBridge.getAuthStatus().getOrNull()
                    val subs = NativeBridge.getSubscriptions().getOrNull()?.size ?: 0
                    val msg = """{"ok":true,"tvSignedIn":${auth?.tvSignedIn == true},"webConnected":${auth?.webConnected == true},"webAccount":${NativeBridge.json.encodeToString(auth?.webAccountName)},"subscriptions":$subs}"""
                    writeJson(output, 200, msg)
                }
                method == "POST" && path == "/api/open-url" -> {
                    val url = extractTextPayload(bodyBytes, contentType, "url")
                    if (url.isBlank()) {
                        writeJson(output, 400, """{"ok":false,"error":"Empty URL"}""")
                    } else {
                        val res = NativeBridge.resolveUrl(url)
                        res.fold(
                            onSuccess = { resolved ->
                                onOpenRoute(resolved)
                                writeJson(output, 200, """{"ok":true,"kind":"${resolved.kind}","id":"${resolved.id}"}""")
                            },
                            onFailure = { e ->
                                writeJson(output, 400, """{"ok":false,"error":${NativeBridge.json.encodeToString(e.message ?: "Failed")}}""")
                            }
                        )
                    }
                }
                method == "POST" && path == "/api/import-cookies" -> {
                    val text = extractTextPayload(bodyBytes, contentType, "file")
                    val res = NativeBridge.importCookiesTxt(text)
                    res.fold(
                        onSuccess = { name ->
                            writeJson(output, 200, """{"ok":true,"account":${NativeBridge.json.encodeToString(name)}}""")
                        },
                        onFailure = { e ->
                            writeJson(output, 400, """{"ok":false,"error":${NativeBridge.json.encodeToString(e.message ?: "Import failed")}}""")
                        }
                    )
                }
                method == "POST" && path == "/api/import-subs" -> {
                    val text = extractTextPayload(bodyBytes, contentType, "file")
                    val res = NativeBridge.importContent(text)
                    res.fold(
                        onSuccess = { rep ->
                            writeJson(output, 200, """{"ok":true,"subscriptions":${rep.subscriptions},"history":${rep.history},"playlists":${rep.playlists},"playlistVideos":${rep.playlistVideos}}""")
                        },
                        onFailure = { e ->
                            writeJson(output, 400, """{"ok":false,"error":${NativeBridge.json.encodeToString(e.message ?: "Import failed")}}""")
                        }
                    )
                }
                method == "GET" && path == "/api/export-backup" -> {
                    val res = NativeBridge.exportBackupJson()
                    res.fold(
                        onSuccess = { jsonStr ->
                            val bytes = jsonStr.toByteArray(StandardCharsets.UTF_8)
                            val hdr = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Disposition: attachment; filename=\"streamtube-backup.json\"\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                            output.write(hdr.toByteArray())
                            output.write(bytes)
                            output.flush()
                        },
                        onFailure = { e ->
                            writeJson(output, 500, """{"ok":false,"error":${NativeBridge.json.encodeToString(e.message ?: "Export failed")}}""")
                        }
                    )
                }
                else -> writeJson(output, 404, """{"ok":false,"error":"Not found"}""")
            }
        }
    }

    private fun extractTextPayload(body: ByteArray, contentType: String, field: String): String {
        val raw = body.toString(StandardCharsets.UTF_8)
        if (contentType.contains("multipart/form-data")) {
            val boundary = contentType.substringAfter("boundary=", "").trim().trim('"')
            if (boundary.isNotEmpty()) {
                val parts = raw.split("--$boundary")
                for (part in parts) {
                    val idx = part.indexOf("\r\n\r\n")
                    if (idx > 0) {
                        val content = part.substring(idx + 4).removeSuffix("\r\n")
                        if (content.isNotBlank()) return content
                    }
                }
            }
        }
        if (contentType.contains("application/x-www-form-urlencoded")) {
            for (pair in raw.split("&")) {
                val kv = pair.split("=", limit = 2)
                if (kv.size == 2 && kv[0] == field) {
                    return URLDecoder.decode(kv[1], "UTF-8")
                }
            }
        }
        return raw
    }

    private fun writeHtml(out: OutputStream, html: String) {
        val bytes = html.toByteArray(StandardCharsets.UTF_8)
        val hdr = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        out.write(hdr.toByteArray())
        out.write(bytes)
        out.flush()
    }

    private fun writeJson(out: OutputStream, code: Int, json: String) {
        val bytes = json.toByteArray(StandardCharsets.UTF_8)
        val status = if (code == 200) "OK" else if (code == 400) "Bad Request" else "Error"
        val hdr = "HTTP/1.1 $code $status\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        out.write(hdr.toByteArray())
        out.write(bytes)
        out.flush()
    }

    companion object {
        private val INDEX_HTML = """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8"/>
<meta name="viewport" content="width=device-width,initial-scale=1"/>
<title>M. ASSAHTOUT TV Companion</title>
<style>
  body { font-family: system-ui, -apple-system, sans-serif; background:#0d0d12; color:#f4f4f6; max-width:600px; margin:24px auto; padding:0 16px; }
  h1 { font-size:1.4rem; color:#ff2a4b; margin-bottom:4px; }
  p.sub { color:#9ca3af; font-size:0.9rem; margin-top:0; }
  .card { background:#171720; border:1px solid #272736; border-radius:14px; padding:16px; margin:16px 0; }
  h2 { font-size:1.05rem; margin:0 0 10px; }
  input[type=text], textarea { width:100%; box-sizing:border-box; padding:10px 12px; border-radius:8px; border:1px solid #323244; background:#0d0d12; color:#fff; font-size:0.95rem; }
  input[type=file] { width:100%; margin:8px 0; color:#d1d5db; }
  button { background:#ff0033; color:#fff; border:none; border-radius:8px; padding:10px 16px; font-weight:600; cursor:pointer; margin-top:10px; }
  button.sec { background:#272736; }
  .msg { margin-top:10px; font-size:0.9rem; color:#4ade80; min-height:20px; }
  .err { color:#f87171; }
</style>
</head>
<body>
  <h1>M. ASSAHTOUT TV Companion</h1>
  <p class="sub" id="status">Connected to your TV</p>

  <div class="card">
    <h2>Send YouTube Link to TV</h2>
    <input id="url" type="text" placeholder="https://www.youtube.com/watch?v=... or @channel"/>
    <button onclick="sendUrl()">Play / Open on TV</button>
    <div id="urlMsg" class="msg"></div>
  </div>

  <div class="card">
    <h2>Import Browser cookies.txt (Google Sync)</h2>
    <p class="sub">Export YouTube cookies using a "Get cookies.txt LOCALLY" browser extension to sync watch history & age-restricted videos.</p>
    <input id="cookieFile" type="file" accept=".txt"/>
    <button onclick="uploadCookies()">Upload cookies.txt</button>
    <div id="cookieMsg" class="msg"></div>
  </div>

  <div class="card">
    <h2>Import Subscriptions / Backup</h2>
    <p class="sub">Supports NewPipe JSON, OPML/XML, YouTube Takeout CSV/JSON, FreeTube .db, or backup JSON exports.</p>
    <input id="subFile" type="file" accept=".json,.opml,.xml,.csv,.db"/>
    <button onclick="uploadSubs()">Import File</button>
    <button class="sec" onclick="window.location='/api/export-backup'">Download Backup JSON</button>
    <div id="subMsg" class="msg"></div>
  </div>

<script>
async function refreshStatus() {
  try {
    const r = await fetch('/api/status').then(x => x.json());
    document.getElementById('status').textContent =
      'Local subscriptions: ' + r.subscriptions +
      (r.webConnected ? ' | Browser session: ' + (r.webAccount || 'connected') : '') +
      (r.tvSignedIn ? ' | TV Account: signed in' : '');
  } catch {}
}
refreshStatus();

async function sendUrl() {
  const el = document.getElementById('urlMsg');
  const url = document.getElementById('url').value.trim();
  const r = await fetch('/api/open-url', { method:'POST', body:url }).then(x => x.json());
  el.className = r.ok ? 'msg' : 'msg err';
  el.textContent = r.ok ? 'Opened ' + r.kind + ' (' + r.id + ') on TV!' : r.error;
}

async function uploadCookies() {
  const el = document.getElementById('cookieMsg');
  const f = document.getElementById('cookieFile').files[0];
  if (!f) { el.className='msg err'; el.textContent='Pick a cookies.txt file first'; return; }
  const text = await f.text();
  const r = await fetch('/api/import-cookies', { method:'POST', body:text }).then(x => x.json());
  el.className = r.ok ? 'msg' : 'msg err';
  el.textContent = r.ok ? 'Connected Google account: ' + r.account : r.error;
  refreshStatus();
}

async function uploadSubs() {
  const el = document.getElementById('subMsg');
  const f = document.getElementById('subFile').files[0];
  if (!f) { el.className='msg err'; el.textContent='Pick a file first'; return; }
  const text = await f.text();
  const r = await fetch('/api/import-subs', { method:'POST', body:text }).then(x => x.json());
  el.className = r.ok ? 'msg' : 'msg err';
  el.textContent = r.ok
    ? 'Imported ' + r.subscriptions + ' subs, ' + r.history + ' history, ' + r.playlists + ' playlists'
    : r.error;
  refreshStatus();
}
</script>
</body>
</html>
        """.trimIndent()
    }
}
