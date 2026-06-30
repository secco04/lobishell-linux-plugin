package de.lobianco.saftssh.linux

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

class InfoActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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

                No configuration is required here.
            """.trimIndent()

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

        card.addView(title)
        card.addView(subtitle)
        card.addView(description)
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