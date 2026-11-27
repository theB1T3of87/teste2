package com.foxgirlsupremacy.companion

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ScrollView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.documentfile.provider.DocumentFile
import java.io.File
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider

private const val MAX_IMPORT_DEPTH = 5

class MainActivity : AppCompatActivity() {

    private lateinit var settings: Settings

    private lateinit var companionSwitch: MaterialSwitch
    private lateinit var reactionsSwitch: MaterialSwitch
    private lateinit var wanderSwitch: MaterialSwitch
    private lateinit var faceTrackingSwitch: MaterialSwitch
    private lateinit var snowSwitch: MaterialSwitch
    private lateinit var modelGroup: ChipGroup
    private lateinit var menuColorGroup: ChipGroup
    private lateinit var animContainer: LinearLayout
    private lateinit var sizeSlider: Slider
    private lateinit var sizeValue: TextView
    private lateinit var permissionsText: TextView
    private lateinit var grantButton: MaterialButton

    private val overlayPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            syncFromPermissions()
        }

    private val notifPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {

            requestMissingPermissions()
        }

    private val cameraPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        settings.faceTrackingEnabled = granted
        faceTrackingSwitch.isChecked = granted
        if (!granted) Toast.makeText(this, R.string.camera_permission_denied, Toast.LENGTH_LONG).show()
        if (settings.companionEnabled && permissionsGranted()) OverlayService.start(this)
    }

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) importModel(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        settings = Settings(this)

        setTheme(
            if (settings.snowFox) R.style.Theme_FoxGirlSupremacy_Snow
            else R.style.Theme_FoxGirlSupremacy,
        )
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val rootScroll = findViewById<ScrollView>(R.id.root_scroll)
        ViewCompat.setOnApplyWindowInsetsListener(rootScroll) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, bars.top, v.paddingRight, bars.bottom)
            insets
        }

        companionSwitch = findViewById(R.id.switch_companion)
        reactionsSwitch = findViewById(R.id.switch_reactions)
        wanderSwitch = findViewById(R.id.switch_wander)
        faceTrackingSwitch = findViewById(R.id.switch_face_tracking)
        snowSwitch = findViewById(R.id.switch_snow)
        modelGroup = findViewById(R.id.model_group)
        menuColorGroup = findViewById(R.id.menu_color_group)
        menuColorGroup = findViewById(R.id.menu_color_group)
        findViewById<MaterialButton>(R.id.button_import).setOnClickListener {
            importLauncher.launch(null)
        }
        animContainer = findViewById(R.id.anim_container)
        sizeSlider = findViewById(R.id.slider_size)
        sizeValue = findViewById(R.id.text_size_value)
        permissionsText = findViewById(R.id.text_permissions)
        grantButton = findViewById(R.id.button_grant)

        bindControls()
    }

    override fun onResume() {
        super.onResume()
        syncFromPermissions()

        if (settings.companionEnabled && permissionsGranted()) {
            OverlayService.start(this)
        }
    }

    private fun bindControls() {

        companionSwitch.isChecked = settings.companionEnabled
        reactionsSwitch.isChecked = settings.reactionsEnabled
        wanderSwitch.isChecked = settings.wanderEnabled
        faceTrackingSwitch.isChecked = settings.faceTrackingEnabled
        snowSwitch.isChecked = settings.snowFox
        sizeSlider.value = settings.scale.coerceIn(0.5f, 5.0f)
        updateSizeLabel(settings.scale)
        populateModels()
        populateMenuColors()
        populateAnimations()

        companionSwitch.setOnCheckedChangeListener { _, checked ->
            settings.companionEnabled = checked
            if (checked) {
                if (permissionsGranted()) OverlayService.start(this) else requestMissingPermissions()
            } else {
                OverlayService.stop(this)
            }
        }

        reactionsSwitch.setOnCheckedChangeListener { _, checked ->
            settings.reactionsEnabled = checked
        }

        wanderSwitch.setOnCheckedChangeListener { _, checked ->
            settings.wanderEnabled = checked
        }

        faceTrackingSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            } else {
                settings.faceTrackingEnabled = checked
                if (settings.companionEnabled && permissionsGranted()) OverlayService.start(this)
            }
        }

        snowSwitch.setOnCheckedChangeListener { _, checked ->
            settings.snowFox = checked

            recreate()
        }

        modelGroup.setOnCheckedStateChangeListener { group, checkedIds ->
            val checkedId = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            val modelId = group.findViewById<Chip>(checkedId)?.tag as? String ?: return@setOnCheckedStateChangeListener
            if (modelId != settings.modelId) {
                settings.modelId = modelId

                populateAnimations()
            }
        }

        sizeSlider.addOnChangeListener { _, value, _ ->
            settings.scale = value
            updateSizeLabel(value)
        }

        grantButton.setOnClickListener { requestMissingPermissions() }
    }

    private fun updateSizeLabel(scale: Float) {
        sizeValue.text = "${(scale * 100).toInt()} %"
    }

    private fun importModel(treeUri: Uri) {
        val tree = DocumentFile.fromTreeUri(this, treeUri) ?: return
        val id = (tree.name ?: "modele")
            .lowercase()
            .replace(Regex("[^a-z0-9_]+"), "_")
            .trim('_')
            .ifEmpty { "modele" }

        val importBtn = findViewById<MaterialButton>(R.id.button_import)
        importBtn.isEnabled = false
        importBtn.setText(R.string.import_progress)
        Thread {
            val dest = File(Live2DModels.importRoot(this), id)
            try {
                if (dest.exists()) dest.deleteRecursively()
                copyTree(tree, dest)

                val model = findFirstModelDir(dest, MAX_IMPORT_DEPTH)
                runOnUiThread {
                    importBtn.isEnabled = true
                    importBtn.setText(R.string.import_model)
                    if (model != null) {
                        settings.modelId = model.name
                        populateModels()
                        populateAnimations()
                        Toast.makeText(this, getString(R.string.import_done, model.name), Toast.LENGTH_LONG).show()
                    } else {
                        dest.deleteRecursively()
                        Toast.makeText(this, R.string.import_invalid, Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                dest.deleteRecursively()
                runOnUiThread {
                    importBtn.isEnabled = true
                    importBtn.setText(R.string.import_model)
                    Toast.makeText(this, R.string.import_failed, Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun findFirstModelDir(dir: File, depth: Int): File? {
        val children = dir.listFiles() ?: return null
        if (children.any { it.name.endsWith(".model3.json") }) return dir
        if (depth <= 0) return null
        for (child in children.filter { it.isDirectory }) {
            findFirstModelDir(child, depth - 1)?.let { return it }
        }
        return null
    }

    private fun copyTree(dir: DocumentFile, dest: File) {
        dest.mkdirs()
        for (child in dir.listFiles()) {
            val name = child.name ?: continue
            val target = File(dest, name)
            if (child.isDirectory) {
                copyTree(child, target)
            } else {
                contentResolver.openInputStream(child.uri)?.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
    }

    private fun populateMenuColors() {
        val palette = listOf(
            "Red" to "#F44336", "Orange" to "#FF9800", "Yellow" to "#FDD835",
            "Green" to "#4CAF50", "Blue" to "#2196F3", "Cyan" to "#00BCD4",
            "Pink" to "#E91E63", "Purple" to "#9C27B0",
        )
        menuColorGroup.removeAllViews()
        for ((name, hex) in palette) {
            val chip = Chip(this).apply {
                text = name
                isCheckable = true
                chipBackgroundColor = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor(hex))
                setTextColor(if (name == "Yellow") android.graphics.Color.BLACK else android.graphics.Color.WHITE)
                isChecked = settings.menuColor.equals(hex, ignoreCase = true)
                setOnClickListener { settings.menuColor = hex; isChecked = true }
            }
            menuColorGroup.addView(chip)
        }
    }

    private fun populateMenuColors() {
        val palette = listOf(
            "Red" to "#F44336", "Orange" to "#FF9800", "Yellow" to "#FDD835",
            "Green" to "#4CAF50", "Blue" to "#2196F3", "Cyan" to "#00BCD4",
            "Pink" to "#E91E63", "Purple" to "#9C27B0",
        )
        menuColorGroup.removeAllViews()
        for ((name, hex) in palette) {
            val chip = Chip(this).apply {
                text = name
                isCheckable = true
                chipBackgroundColor = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor(hex))
                setTextColor(if (name == "Yellow") android.graphics.Color.BLACK else android.graphics.Color.WHITE)
                isChecked = settings.menuColor.equals(hex, ignoreCase = true)
                setOnClickListener { settings.menuColor = hex; isChecked = true }
            }
            menuColorGroup.addView(chip)
        }
    }

    private fun populateAnimations() {
        animContainer.removeAllViews()
        val entry = Live2DModels.resolve(this, settings.modelId) ?: return
        val inv = Live2DModels.inventory(this, entry)
        val mapping = settings.animMapping()

        if (inv.expressions.isEmpty() && inv.motions.isEmpty()) {
            animContainer.addView(animSub(getString(R.string.anim_empty)))
            return
        }
        if (inv.expressions.isNotEmpty()) {
            animContainer.addView(animSection(getString(R.string.anim_expressions)))
            for (name in inv.expressions) {
                addAnimRow(AnimMapping.expressionKey(name), name, mapping) {
                    OverlayService.playExpression(this, name)
                }
            }
        }
        for ((group, list) in inv.motions.groupBy { it.group }) {
            animContainer.addView(
                animSection(if (group.isEmpty()) getString(R.string.anim_motions) else group),
            )
            for (m in list) {
                addAnimRow(AnimMapping.motionKey(m.group, m.index), m.label, mapping) {
                    OverlayService.playMotion(this, m.group, m.index)
                }
            }
        }
    }

    private fun addAnimRow(
        animKey: String,
        label: String,
        mapping: AnimMapping,
        preview: () -> Unit,
    ) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val previewBtn = MaterialButton(
            this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle,
        ).apply {
            text = label
            gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { preview() }
        }
        val weightBtn = MaterialButton(
            this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle,
        ).apply {
            text = "×${mapping.weightOf(animKey)}"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = dp(6) }
            setOnClickListener {
                val next = mapping.weightOf(animKey) % AnimMapping.MAX_WEIGHT + 1
                mapping.setWeight(animKey, next)
                text = "×$next"
            }
        }
        topRow.addView(previewBtn)
        topRow.addView(weightBtn)
        row.addView(topRow)

        val chips = ChipGroup(this).apply { isSingleLine = false }
        for ((actionId, actionLabel) in AnimMapping.ACTIONS) {
            val chip = Chip(this).apply {
                text = actionLabel
                isCheckable = true
                isChecked = actionId in mapping.actionsOf(animKey)
                setOnClickListener { mapping.toggle(animKey, actionId) }
            }
            chips.addView(chip)
        }
        row.addView(chips)

        animContainer.addView(row)
    }

    private fun animSub(text: String) = TextView(this).apply {
        setText(text); textSize = 13f; alpha = 0.7f
    }

    private fun animSection(text: String) = TextView(this).apply {
        setText(text); textSize = 14f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        val tv = android.util.TypedValue()
        theme.resolveAttribute(com.google.android.material.R.attr.colorPrimary, tv, true)
        setTextColor(tv.data)
        setPadding(0, dp(14), 0, 0)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun populateModels() {
        modelGroup.removeAllViews()
        for (model in Live2DModels.list(this)) {
            val chip = Chip(this).apply {
                id = android.view.View.generateViewId()
                text = model.id.replaceFirstChar { it.uppercase() }
                isCheckable = true
                tag = model.id
                isChecked = (model.id == settings.modelId)
            }
            modelGroup.addView(chip)
        }
    }

    private fun requestMissingPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        if (!AndroidSettings.canDrawOverlays(this)) {
            overlayPermissionLauncher.launch(
                Intent(
                    AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"),
                ),
            )
            return
        }

        if (settings.companionEnabled) OverlayService.start(this)
        syncFromPermissions()
    }

    private fun overlayGranted() = AndroidSettings.canDrawOverlays(this)

    private fun notifGranted() =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun permissionsGranted() = overlayGranted() && notifGranted()

    private fun syncFromPermissions() {
        val overlayOk = overlayGranted()
        val notifOk = notifGranted()

        fun line(label: String, ok: Boolean) =
            "${if (ok) "✓" else "✗"} $label — " +
                getString(if (ok) R.string.perm_granted else R.string.perm_missing)

        permissionsText.text = buildString {
            appendLine(line(getString(R.string.perm_overlay), overlayOk))
            append(line(getString(R.string.perm_notif), notifOk))
        }

        grantButton.visibility = if (overlayOk && notifOk) android.view.View.GONE
        else android.view.View.VISIBLE
    }
}
