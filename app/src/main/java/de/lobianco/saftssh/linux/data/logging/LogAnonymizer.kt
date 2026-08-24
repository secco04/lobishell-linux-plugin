package de.lobianco.saftssh.linux.data.logging

/**
 * Sanitizes log text for external sharing — replaces personal connection data (IPs, hostnames,
 * usernames) with neutral placeholders. Ported verbatim from the main LobiShell app's identical
 * class (`de.lobianco.saftssh.data.logging.LogAnonymizer`) — see that file for the full pattern
 * rationale. Applied only when preparing the log for sharing, never to the on-device file itself.
 */
object LogAnonymizer {

    /** Strict IPv4 — won't match version numbers like "1.2.3" (needs 4 octets). */
    private val IPV4 = Regex(
        """\b(25[0-5]|2[0-4]\d|[01]?\d\d?)\.(25[0-5]|2[0-4]\d|[01]?\d\d?)\.(25[0-5]|2[0-4]\d|[01]?\d\d?)\.(25[0-5]|2[0-4]\d|[01]?\d\d?)\b"""
    )

    /** IPv6 — requires 3+ colon-separated groups so timestamps like "19:21:59" never match. */
    private val IPV6 = Regex(
        """(?<![/\w])(?:[0-9a-fA-F]{1,4}:){3,7}[0-9a-fA-F]{0,4}(?!\w)"""
    )

    /** user@host — SSH-style `user@hostname` or `user@ip`; skips package-name-looking hosts. */
    private val USER_AT_HOST = Regex(
        """\b([a-z][a-z0-9_.-]{0,63})@([a-zA-Z0-9][a-zA-Z0-9._-]{1,253})\b"""
    )

    /** hostname:port — excludes .kt/.java/.class suffixes so stack traces are left alone. */
    private val HOST_PORT = Regex(
        """\b([a-zA-Z0-9][a-zA-Z0-9._-]*\.(?!kt\b|java\b|class\b)[a-zA-Z]{2,}):(\d{1,5})\b"""
    )

    private val HOST_SPACE_PORT = Regex(
        """\b([a-zA-Z0-9][a-zA-Z0-9._-]*\.(?!kt\b|java\b|class\b)[a-zA-Z]{2,})\s+port\s+(\d{1,5})\b"""
    )

    private val SSHJ_THREAD_HOST = Regex(
        """/(\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}|[a-zA-Z0-9][a-zA-Z0-9._-]+):(\d{1,5})"""
    )

    /** Apply pattern-based anonymization to [text]. Order matters: IPs first. */
    fun sanitize(text: String): String {
        var r = text
        r = SSHJ_THREAD_HOST.replace(r, "/[x.x.x.x]:\$2")
        r = IPV4.replace(r, "[x.x.x.x]")
        r = IPV6.replace(r, "[::x]")
        r = HOST_PORT.replace(r) { m -> "[host]:${m.groupValues[2]}" }
        r = HOST_SPACE_PORT.replace(r) { m -> "[host] port ${m.groupValues[2]}" }
        r = USER_AT_HOST.replace(r) { m ->
            val user = m.groupValues[1]
            val host = m.groupValues[2]
            if (looksLikePackageName(host)) m.value else "$user@[host]"
        }
        return r
    }

    private fun looksLikePackageName(s: String): Boolean {
        if (s.any { it.isUpperCase() }) return true
        val lower = s.lowercase()
        return lower.startsWith("de.") || lower.startsWith("com.") ||
               lower.startsWith("org.") || lower.startsWith("net.") ||
               lower.startsWith("io.")  || lower.startsWith("uk.") ||
               lower.startsWith("app.")
    }
}
