package de.lobianco.saftssh.linux

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class InfoActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Best-effort: this is the plugin's only Activity, so it's the only place that can show a
        // runtime permission dialog. Needed for the LinuxSessionService foreground-service
        // notification (see LinuxSessionService.promoteToForeground) to actually be visible — the
        // service keeps its priority protection either way, this just controls whether the user
        // sees it.
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(32, 32, 32, 32)

            background = GradientDrawable().apply {
                setColor(Color.rgb(18, 20, 28))
            }
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)

            background = GradientDrawable().apply {
                setColor(Color.rgb(35, 39, 52))
                cornerRadius = 32f
            }
        }


        val title = TextView(this).apply {
            text = "LobiShell\nLinux Plugin"
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(0, 16, 0, 8)
        }

        val subtitle = TextView(this).apply {
            text = "Local Linux userland provider"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(170, 180, 200))
            setPadding(0, 0, 0, 32)
        }

        val description = TextView(this).apply {
            text = """
                This plugin provides a Linux userland environment
                for the LobiShell app.

                A Linux userland contains the user-space tools,
                libraries, utilities, and command-line programs
                needed to run Linux software.

                It does not include a separate Linux kernel.
                Instead, it uses the Android system as the base
                platform and provides Linux-compatible tools
                inside LobiShell.

                This plugin only works together with LobiShell.
                It is not a standalone application and cannot
                be used without the main LobiShell app.
            """.trimIndent() + if (BuildConfig.SUPPORTS_ROOT_CONTAINERS)
                "\n\nThis is the root build — see below."
            else
                "\n\nNo configuration is required here."

            textSize = 15f
            setTextColor(Color.rgb(220, 225, 235))
            setLineSpacing(8f, 1f)
        }

        val badge = TextView(this).apply {
            text = "PLUGIN COMPONENT"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(120, 200, 255))
            setPadding(0, 32, 0, 0)
        }

        // Opening this Activity is also the fix for a real Android platform restriction: a
        // freshly installed/updated app starts in a "stopped" state in which NO other app may
        // bindService() into it (ApplicationInfo.FLAG_STOPPED) — LobiShell's own bind attempt then
        // fails with a raw "Not allowed to bind to service" SecurityException until this Activity
        // is opened at least once. Landing here BECAUSE of that error (rather than by choice) means
        // there's nothing to actually do here except go back — so give that an explicit button
        // instead of making the user hunt for LobiShell's icon themselves.
        val openAppButton = Button(this).apply {
            text = "Open LobiShell"
            setPadding(0, 24, 0, 0)
            setOnClickListener {
                val launchIntent = packageManager.getLaunchIntentForPackage("de.lobianco.saftssh")
                if (launchIntent != null) {
                    startActivity(launchIntent)
                } else {
                    Toast.makeText(this@InfoActivity, "LobiShell isn't installed", Toast.LENGTH_SHORT).show()
                }
            }
        }

        card.addView(title)
        card.addView(subtitle)
        card.addView(description)
        card.addView(openAppButton)

        // ── Root flavor only: explicit, user-initiated "Grant Root Access" moment ───────────
        // A deliberate button tap here — with this Activity actually in the foreground and
        // visible — is a much cleaner trigger for the su prompt than the first time
        // LinuxSessionService silently calls `su -c ...` from a background bound-service
        // context when the user opens a Linux tab in the main app. That silent, out-of-nowhere
        // prompt is also the more suspicious-looking context for Magisk's own tapjacking check.
        if (BuildConfig.SUPPORTS_ROOT_CONTAINERS) {
            val rootStatus = TextView(this).apply {
                // isDeviceRooted() is a cheap static heuristic (file paths + Magisk package name)
                // that can false-negative on modern setups (Magisk hides its su mount from
                // unprivileged processes, and offers an app-name randomizer specifically to defeat
                // the package-name check) — so this label is phrased as a hint, not a verdict. The
                // button below runs the real, authoritative `su -c id` test.
                text = if (RootDetector.isDeviceRooted(this@InfoActivity))
                    "Root indicators found on this device."
                else
                    "No common root indicators found — tap below to test for sure."
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(170, 180, 200))
                setPadding(0, 24, 0, 12)
            }

            val grantResult = TextView(this).apply {
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(220, 225, 235))
                setPadding(0, 12, 0, 0)
            }

            val grantButton = Button(this).apply {
                text = "Grant Root Access"
                setOnClickListener {
                    isEnabled = false
                    grantResult.text = "Requesting root — check for a superuser prompt…"
                    Thread {
                        val granted = RootDetector.hasWorkingRootAccess()
                        runOnUiThread {
                            grantResult.text = if (granted)
                                "✓ Root access granted — container support is ready."
                            else
                                "✗ Root access denied or su not found. If a superuser prompt " +
                                "couldn't be confirmed, check Settings → Apps → Special app " +
                                "access → \"Display over other apps\" for anything else enabled " +
                                "there, and disable it — that blocks Magisk's prompt as a " +
                                "tapjacking precaution."
                            isEnabled = true
                        }
                    }.start()
                }
            }

            card.addView(rootStatus)
            card.addView(grantButton)
            card.addView(grantResult)
        }

        card.addView(badge)

        root.addView(
            card,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        setContentView(root)
    }
}