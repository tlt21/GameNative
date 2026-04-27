package app.gamenative.gamefixes

import android.content.Context
import app.gamenative.data.GameSource
import com.winlator.container.Container
import com.winlator.core.TarCompressorUtils
import com.winlator.core.WineRegistryEditor
import timber.log.Timber
import java.io.File

/**
 * Danganronpa 2: Goodbye Despair (Steam App ID 413420)
 *
 * After launching another game and returning to DR2, the game crashes on any scene with
 * shader compilation. The symptom is E5017 ("Flatten 'if' conditionals branches") logged
 * by vkd3d-shader, causing a hard crash shortly after the title screen.
 *
 * Root cause: applyGeneralPatches() runs when the app or image version changes. It resets
 * user.reg from the container template (removing all DLL overrides) and replaces all common
 * DLLs — including d3dx9_43 — with Proton stubs. The wincomponent re-extraction that would
 * normally restore the real Microsoft DLLs is then skipped because the wincomponents setting
 * has not changed. The game is permanently broken until a full container reset.
 *
 * Why the Proton stubs fail: all Proton-bundled DLLs are Wine-compiled "builtin" PE files.
 * Wine identifies them as builtin via PE metadata, so they load as builtin even when the
 * DLL override says "native,builtin". Wine's builtin d3dx9_43 routes HLSL shader compilation
 * through d3dcompiler_47 (vkd3d-shader), which does not support the [flatten] attribute →
 * E5017.
 *
 * Fix: DR2 requires the real Microsoft d3dx9_43 and d3dcompiler_43 DLLs from the direct3d
 * wincomponent (wincomponents/direct3d.tzst). The real MS d3dx9_43 loads as native and
 * compiles shaders internally via d3dcompiler_43 (also native MS DLL), bypassing vkd3d
 * entirely. Two steps are applied every launch:
 * 1. If d3dx9_43=native,builtin is absent from user.reg (a reliable signal that
 *    applyGeneralPatches() has reset both the registry and the DLLs), re-extract
 *    wincomponents/direct3d.tzst to restore the full real MS DLL set.
 * 2. Always write "native,builtin" overrides for d3dx9_43 and d3dcompiler_43 to user.reg
 *    so they survive any future user.reg reset.
 */
val STEAM_Fix_413420: KeyedGameFix = object : KeyedGameFix {
    override val gameSource = GameSource.STEAM
    override val gameId = "413420"

    override fun apply(
        context: Context,
        gameId: String,
        installPath: String,
        installPathWindows: String,
        container: Container,
    ): Boolean {
        return try {
            ensureNativeDirectXDlls(context, container)
            true
        } catch (e: Exception) {
            Timber.tag("GameFixes").e(e, "Failed to apply fixes for Danganronpa 2")
            false
        }
    }

    /**
     * Restores real Microsoft DirectX DLLs and ensures they load as native.
     *
     * When applyGeneralPatches() resets the container, it clears user.reg and replaces
     * DLLs with Proton stubs in one coupled operation. Checking for d3dx9_43=native,builtin
     * in user.reg is therefore a reliable signal for both resets: if the override is absent,
     * wincomponents/direct3d.tzst is re-extracted to restore the real MS DLL set. Registry
     * overrides are then written (or re-written) every launch regardless.
     */
    private fun ensureNativeDirectXDlls(context: Context, container: Container) {
        val userRegFile = File(container.getRootDir(), ".wine/user.reg")
        val needsExtraction = if (!userRegFile.exists()) {
            true
        } else {
            WineRegistryEditor(userRegFile).use { editor ->
                editor.getStringValue("Software\\Wine\\DllOverrides", "d3dx9_43", null) == null
            }
        }

        if (needsExtraction) {
            val windowsDir = File(container.getRootDir(), ".wine/drive_c/windows")
            val extracted = TarCompressorUtils.extract(
                TarCompressorUtils.Type.ZSTD, context.assets,
                "wincomponents/direct3d.tzst", windowsDir
            )
            if (extracted) {
                Timber.tag("GameFixes").i("Extracted direct3d.tzst: d3dx9_43 override was absent (user.reg was reset)")
            } else {
                Timber.tag("GameFixes").w("Failed to extract direct3d.tzst; game may crash")
            }
        }

        setNativeOverrides(container)
    }

    /**
     * Writes "native,builtin" overrides for d3dx9_43 and d3dcompiler_43 to user.reg.
     *
     * Without these overrides, Wine loads Proton stubs as builtin (even if real MS DLLs
     * are present) and routes shader compilation through vkd3d → E5017. Overrides are
     * written to HKCU\Software\Wine\DllOverrides (user.reg) every launch because
     * applyGeneralPatches() may reset user.reg at any time. HKLM overrides are ignored
     * by Wine.
     */
    private fun setNativeOverrides(container: Container) {
        val userRegFile = File(container.getRootDir(), ".wine/user.reg")
        if (!userRegFile.exists()) {
            Timber.tag("GameFixes").w("user.reg not found, cannot set DLL overrides")
            return
        }
        try {
            WineRegistryEditor(userRegFile).use { editor ->
                editor.setCreateKeyIfNotExist(true)
                val key = "Software\\Wine\\DllOverrides"
                editor.setStringValue(key, "d3dx9_43", "native,builtin")
                editor.setStringValue(key, "d3dcompiler_43", "native,builtin")
            }
            Timber.tag("GameFixes").i("Set d3dx9_43, d3dcompiler_43=native,builtin in user.reg")
        } catch (e: Exception) {
            Timber.tag("GameFixes").e(e, "Failed to set DLL overrides in user.reg")
        }
    }
}
