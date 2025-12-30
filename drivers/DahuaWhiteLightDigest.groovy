/**
 *  Dahua WhiteLight (Digest) w/ Brightness + Polling + Retry/Timeout
 *
 *  Endpoints:
 *   - Status:  /cgi-bin/coaxialControlIO.cgi?action=getStatus&channel=1
 *   - On/Off:  /cgi-bin/coaxialControlIO.cgi?action=control&channel=1&info[0].Type=1&info[0].IO=1|2
 *   - Level:   /cgi-bin/configManager.cgi?action=setConfig&Lighting_V2[0][P][1]... (P=0..2)
 *   - Level read: /cgi-bin/configManager.cgi?action=getConfig&name=Lighting_V2
 */

import groovy.transform.Field
import java.security.MessageDigest

metadata {
    definition(name: "Dahua WhiteLight (Digest) + Brightness", namespace: "stephen", author: "ChatGPT") {
        capability "Switch"
        capability "SwitchLevel"
        capability "Refresh"
        capability "Initialize"
    }

    preferences {
        input name: "camIP", type: "text", title: "Camera IP", required: true
        input name: "camPort", type: "number", title: "Camera Port", defaultValue: 80, required: true
        input name: "camUser", type: "text", title: "Username", required: true
        input name: "camPass", type: "password", title: "Password", required: true

        input name: "channel", type: "number", title: "Channel", defaultValue: 1, required: true

        input name: "pollMins", type: "number", title: "Polling interval (minutes, 0=disabled)", defaultValue: 1
        input name: "httpTimeoutSecs", type: "number", title: "HTTP timeout seconds", defaultValue: 8
        input name: "maxRetries", type: "number", title: "Max retries (per request)", defaultValue: 1

        input name: "debugLogging", type: "bool", title: "Enable debug logging", defaultValue: true
    }
}

// ---- constants (Hubitat-friendly) ----
@Field final Integer SECURITY_LIGHT_TYPE = 1   // WhiteLight control type
@Field final Integer IO_ON  = 1
@Field final Integer IO_OFF = 2
@Field final String DRIVER_VERSION = "1.0.1"

// ---- lifecycle ----
void installed() {
    if (debugLogging) runIn(1800, "logsOff")
    state.driverVersion = DRIVER_VERSION
    log.info "Dahua WhiteLight driver v${DRIVER_VERSION} installed"
    initialize()
}

void updated() {
    unschedule()
    if (debugLogging) runIn(1800, "logsOff")
    state.driverVersion = DRIVER_VERSION
    log.info "Dahua WhiteLight driver v${DRIVER_VERSION} updated"
    initialize()
}

void initialize() {
    setupPolling()
    refresh()
}

void logsOff() {
    device.updateSetting("debugLogging", [value: "false", type: "bool"])
    log.info "Debug logging disabled"
}

void setupPolling() {
    Integer m = ((pollMins ?: 0) as Integer)
    if (m > 0) {
        // Hubitat cron: sec min hour day month dow
        if (m >= 60) {
            Integer h = Math.max(1, (m / 60) as Integer)
            schedule("0 0 */${h} ? * *", "refresh")
            logDebug "Polling enabled: every ${h} hour(s)"
        } else {
            schedule("0 */${m} * ? * *", "refresh")
            logDebug "Polling enabled: every ${m} minute(s)"
        }
    } else {
        logDebug "Polling disabled"
    }
}

// ---- commands ----
void on() {
    Integer lvl = (device.currentValue("level") ?: 100) as Integer
    if (lvl < 1) lvl = 100
    // Ensure manual brightness config exists, then flip light on
    setLevel(lvl)
    coaxialControl(true)
    runIn(1, "refresh")
}

void off() {
    coaxialControl(false)
    sendEvent(name: "switch", value: "off")
    runIn(1, "refresh")
}

void setLevel(Number value) {
    Integer lvl = Math.max(0, Math.min(100, (value ?: 0) as Integer))
    if (lvl == 0) {
        off()
        sendEvent(name: "level", value: 0, unit: "%")
        return
    }

    // Set WhiteLight brightness across profiles 0..2
    for (Integer p in [0, 1, 2]) {
        String path = "/cgi-bin/configManager.cgi?action=setConfig" +
                "&Lighting_V2[0][${p}][1].Mode=Manual" +
                "&Lighting_V2[0][${p}][1].State=On" +
                "&Lighting_V2[0][${p}][1].MiddleLight[0].Light=${lvl}"
        dahuaGet(path, true) // verify OK
    }

    sendEvent(name: "level", value: lvl, unit: "%")
    sendEvent(name: "switch", value: "on")
}

void refresh() {
    // 1) Switch state from coaxial status
    Map st = dahuaGet("/cgi-bin/coaxialControlIO.cgi?action=getStatus&channel=${safeChannel()}", false)
    String wl = st?.get("status.status.WhiteLight")
    if (wl) {
        String sw = wl.equalsIgnoreCase("On") ? "on" : "off"
        sendEvent(name: "switch", value: sw)
    } else {
        logDebug "refresh: status.status.WhiteLight missing. Keys=${st?.keySet()}"
    }

    // 2) Brightness readback from Lighting_V2 (best-effort)
    Map lv2 = dahuaGet("/cgi-bin/configManager.cgi?action=getConfig&name=Lighting_V2", false)
    Integer level = extractWhiteLightLevel(lv2)
    if (level != null) {
        sendEvent(name: "level", value: level, unit: "%")
    }
}

// ---- camera ops ----
void coaxialControl(Boolean enable) {
    String io = enable ? "${IO_ON}" : "${IO_OFF}"
    String path = "/cgi-bin/coaxialControlIO.cgi?action=control" +
            "&channel=${safeChannel()}" +
            "&info[0].Type=${SECURITY_LIGHT_TYPE}" +
            "&info[0].IO=${io}"
    dahuaGet(path, true) // verify OK
    sendEvent(name: "switch", value: enable ? "on" : "off")
}

Integer safeChannel() {
    Integer ch = (channel ?: 1) as Integer
    if (ch < 1) ch = 1
    return ch
}

// ---- digest + retry ----
Map dahuaGet(String path, Boolean verifyOk) {
    Integer tries = Math.max(0, (maxRetries ?: 0) as Integer) + 1
    Exception lastEx = null

    for (int attempt = 1; attempt <= tries; attempt++) {
        try {
            return dahuaGetOnce(path, verifyOk)
        } catch (Exception e) {
            lastEx = e
            String msg = (e?.message ?: "").toLowerCase()
            boolean likelyAuth = msg.contains("401") || msg.contains("unauthorized") || msg.contains("www-authenticate")
            boolean likelyTimeout = msg.contains("timed out") || msg.contains("timeout")
            logDebug "HTTP attempt ${attempt}/${tries} failed: ${e.class.simpleName}: ${e.message}"

            if (likelyAuth) state.digestChallenge = null

            if (attempt < tries && (likelyAuth || likelyTimeout)) {
                pauseExecution(250L * attempt)
                continue
            }
            break
        }
    }

    throw lastEx ?: new Exception("Unknown Dahua request failure")
}

Map dahuaGetOnce(String path, Boolean verifyOk) {
    String url = baseUrl() + path
    Map ch = getOrFetchChallenge(path)

    String authHeader = buildDigestAuthHeader(
            camUser?.toString(),
            camPass?.toString(),
            "GET",
            requestUriFromUrl(url),
            ch
    )

    Map params = [
            uri: url,
            headers: [
                    "Authorization": authHeader,
                    "Accept": "*/*",
                    "Connection": "close"
            ],
            timeout: (httpTimeoutSecs ?: 8) as Integer
    ]

    String bodyText = ""
    httpGet(params) { resp ->
        bodyText = readBodyText(resp?.data)
    }

    if (verifyOk) {
        if (!(bodyText?.trim()?.equalsIgnoreCase("OK"))) {
            throw new Exception("verifyOk failed; body='${bodyText}'")
        }
        return ["_raw": bodyText ?: ""]
    }

    return parseKeyValue(bodyText ?: "")
}

String baseUrl() {
    if (!camIP) {
        throw new Exception("Camera IP (camIP) is required")
    }
    Integer p = (camPort ?: 80) as Integer
    return "http://${camIP}:${p}"
}

/**
 * Fetch WWW-Authenticate Digest challenge by provoking 401 and parsing its text.
 */
Map getOrFetchChallenge(String challengePath) {
    Map ch = (state.digestChallenge instanceof Map) ? (Map)state.digestChallenge : null
    if (ch && ch.realm && ch.nonce) return ch

    String path = challengePath ?: "/cgi-bin/magicBox.cgi?action=getSystemInfo"
    String url = baseUrl() + path
    Map parsed = null
    String header = null
    try {
        httpGet([uri: url, timeout: (httpTimeoutSecs ?: 8) as Integer, headers: ["Connection":"close"]]) { resp ->
            header = extractWwwAuthenticateFromResp(resp)
            parsed = parseDigestChallengeFromText(header)
        }
    } catch (Exception e) {
        header = extractWwwAuthenticateFromException(e)
        parsed = parseDigestChallengeFromText(header)
        if (!parsed?.realm || !parsed?.nonce) {
            String err = e?.message ?: e?.toString()
            String errLower = err.toLowerCase()
            if (errLower.contains("name or service not known") || errLower.contains("unknown host") || errLower.contains("no route to host")) {
                throw new Exception("Network error while fetching Digest challenge: ${err}")
            }
            throw new Exception("Could not fetch Digest challenge (realm/nonce missing). Error: ${err}")
        }
    }

    if (!parsed?.realm || !parsed?.nonce) {
        String err = header ? "Invalid Digest header" : "WWW-Authenticate header missing"
        throw new Exception("Could not fetch Digest challenge (realm/nonce missing). Error: ${err}")
    }

    parsed.qop = parsed.qop ?: "auth"
    parsed.algorithm = parsed.algorithm ?: "MD5"
    parsed.cnonce = parsed.cnonce ?: randomHex(16)

    state.digestChallenge = parsed
    state.ncInt = 1

    logDebug "Cached digest challenge: [realm:${parsed.realm}, nonce:${parsed.nonce}, qop:${parsed.qop}, opaque:${parsed.opaque}, algorithm:${parsed.algorithm}]"
    return parsed
}

Map parseDigestChallengeFromText(String text) {
    if (!text) return [:]
    int idx = text.indexOf("Digest ")
    if (idx < 0) idx = text.indexOf("digest ")
    if (idx < 0) return [:]

    String s = text.substring(idx).replaceFirst(/^[Dd]igest\s+/, "")
    Map out = [:]
    s.split(",").each { part ->
        String p = part.trim()
        int eq = p.indexOf("=")
        if (eq > 0) {
            String k = p.substring(0, eq).trim()
            String v = p.substring(eq + 1).trim().replaceAll(/^"|"$/, "")
            out[k] = v
        }
    }
    return out
}

String extractWwwAuthenticateFromException(Exception e) {
    try {
        def resp = e?.response
        String header = extractWwwAuthenticateFromResp(resp)
        if (header) return header
    } catch (Exception ignored) {
    }
    try {
        String text = e?.message ?: e?.toString()
        return extractWwwAuthenticateFromText(text)
    } catch (Exception ignored) {
    }
    return null
}

String extractWwwAuthenticateFromText(String text) {
    if (!text) return null
    int idx = text.indexOf("WWW-Authenticate:")
    if (idx < 0) idx = text.indexOf("www-authenticate:")
    if (idx < 0) return null
    String s = text.substring(idx)
    int nl = s.indexOf("\n")
    if (nl > 0) s = s.substring(0, nl)
    return s.replaceFirst(/^[Ww][Ww][Ww]-[Aa]uthenticate:\s*/, "")
}

String extractWwwAuthenticateFromResp(resp) {
    if (resp == null) return null
    try {
        def headers = resp?.headers
        if (headers) {
            def direct = headers["WWW-Authenticate"] ?: headers["www-authenticate"]
            if (direct) return direct.toString()
            def match = headers.find { k, v -> k?.toString()?.equalsIgnoreCase("WWW-Authenticate") }
            if (match) return match?.value?.toString()
        }
    } catch (Exception ignored) {
    }
    try {
        def headers = resp?.getHeaders()
        if (headers) {
            def direct = headers["WWW-Authenticate"] ?: headers["www-authenticate"]
            if (direct) return direct.toString()
            def match = headers.find { k, v -> k?.toString()?.equalsIgnoreCase("WWW-Authenticate") }
            if (match) return match?.value?.toString()
        }
    } catch (Exception ignored) {
    }
    return null
}

String readBodyText(data) {
    if (data == null) return ""
    if (data instanceof String) return data
    if (data instanceof byte[]) return new String((byte[])data)
    try {
        if (data.respondsTo("getText")) return data.getText()
    } catch (Exception ignored) {
    }
    try {
        if (data.respondsTo("text")) return data.text
    } catch (Exception ignored) {
    }
    return data.toString()
}

String requestUriFromUrl(String url) {
    def u = new java.net.URI(url)
    String q = u.getRawQuery()
    return q ? "${u.getRawPath()}?${q}" : u.getRawPath()
}

String buildDigestAuthHeader(String user, String pass, String method, String uri, Map ch) {
    String realm = ch.realm
    String nonce = ch.nonce
    String qop = ch.qop ?: "auth"
    String opaque = ch.opaque
    String algorithm = ch.algorithm ?: "MD5"
    String cnonce = ch.cnonce ?: randomHex(16)
    ch.cnonce = cnonce
    state.digestChallenge = ch

    String nc = nextNc()

    String ha1 = md5Hex("${user}:${realm}:${pass}")
    String ha2 = md5Hex("${method}:${uri}")
    String response = md5Hex("${ha1}:${nonce}:${nc}:${cnonce}:${qop}:${ha2}")

    String hdr = "Digest " +
            "username=\"${user}\", " +
            "realm=\"${realm}\", " +
            "nonce=\"${nonce}\", " +
            "uri=\"${uri}\", " +
            "algorithm=${algorithm}, " +
            "response=\"${response}\", " +
            "qop=${qop}, " +
            "nc=${nc}, " +
            "cnonce=\"${cnonce}\""
    if (opaque) hdr += ", opaque=\"${opaque}\""
    return hdr
}

String nextNc() {
    Integer n = (state.ncInt instanceof Integer) ? (Integer)state.ncInt : 1
    String nc = String.format("%08x", n)
    state.ncInt = n + 1
    return nc
}

String md5Hex(String s) {
    MessageDigest md = MessageDigest.getInstance("MD5")
    byte[] digest = md.digest(s.getBytes("ISO-8859-1"))
    return digest.collect { String.format("%02x", it) }.join()
}

String randomHex(int bytes) {
    byte[] b = new byte[bytes]
    new Random().nextBytes(b)
    return b.collect { String.format("%02x", it) }.join()
}

// ---- parsing + level extraction ----
Map parseKeyValue(String text) {
    Map out = [:]
    text.split("\n").each { line ->
        String l = line?.trim()
        if (!l) return
        int eq = l.indexOf("=")
        if (eq > 0) out[l.substring(0, eq)] = l.substring(eq + 1)
        else out[l] = l
    }
    return out
}

Integer extractWhiteLightLevel(Map lv2) {
    if (!lv2) return null

    String v = lv2.get("table.Lighting_V2[0][0][1].MiddleLight[0].Light")
    if (v == null) {
        for (Integer p in [0, 1, 2]) {
            String k = "table.Lighting_V2[0][${p}][1].MiddleLight[0].Light"
            if (lv2.containsKey(k)) {
                v = lv2.get(k)
                break
            }
        }
    }
    if (v == null) return null

    try {
        Integer lvl = v.toString().toInteger()
        return Math.max(0, Math.min(100, lvl))
    } catch (Exception ignored) {
        return null
    }
}

void logDebug(String msg) {
    if (debugLogging) log.debug msg
}
