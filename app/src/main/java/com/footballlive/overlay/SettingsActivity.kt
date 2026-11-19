package com.footballlive.overlay

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.switchmaterial.SwitchMaterial

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        val prefs = Prefs(this)
        val root = findViewById<LinearLayout>(R.id.settingsRoot)

        val side = findViewById<RadioGroup>(R.id.sideGroup)
        side.check(if (prefs.panelSide == "left") R.id.sideLeft else R.id.sideRight)
        side.setOnCheckedChangeListener { _, id ->
            prefs.panelSide = if (id == R.id.sideLeft) "left" else "right"
        }

        val sw = findViewById<SwitchMaterial>(R.id.counterSwitch)
        sw.isChecked = prefs.showCounter
        sw.setOnCheckedChangeListener { _, checked -> prefs.showCounter = checked }

        slider(root, "Panel opacity", 30, 95, prefs.opacity, "%") { prefs.opacity = it }
        slider(root, "Panel height", 20, 80, prefs.panelHeight, "% of screen") { prefs.panelHeight = it }
        slider(root, "Chat font size", 10, 20, prefs.fontSize, " sp") { prefs.fontSize = it }
        slider(root, "Maximum visible messages", 10, 200, prefs.maxMessages, "") { prefs.maxMessages = it }
        slider(root, "Floating button size", 40, 80, prefs.buttonSize, " dp") { prefs.buttonSize = it }

        root.addView(TextView(this).apply {
            text = "Changes apply instantly while the overlay is running."
            setTextColor(getColor(R.color.muted))
            textSize = 12f
            setPadding(0, 28, 0, 0)
        })
    }

    private fun slider(
        root: LinearLayout, label: String, min: Int, max: Int, value: Int,
        unit: String, onChange: (Int) -> Unit
    ) {
        val row = layoutInflater.inflate(R.layout.row_slider, root, false)
        val seek = row.findViewById<SeekBar>(R.id.rowSeek)
        val valueText = row.findViewById<TextView>(R.id.rowValue)
        row.findViewById<TextView>(R.id.rowLabel).text = label
        seek.max = max - min
        seek.progress = (value - min).coerceIn(0, max - min)
        valueText.text = "$value$unit"
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, progress: Int, fromUser: Boolean) {
                val v = progress + min
                valueText.text = "$v$unit"
                if (fromUser) onChange(v)
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
        root.addView(row)
    }
}
