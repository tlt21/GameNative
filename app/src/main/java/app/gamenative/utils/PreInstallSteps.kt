package app.gamenative.utils

import app.gamenative.data.GameSource
import app.gamenative.enums.Marker
import app.gamenative.service.gog.GOGService
import com.winlator.container.Container
import java.io.File

/**
 * Determines whether pre-install steps (VC Redist, GOG script interpreter) need to run
 * as Wine guest programs before the game launches. These installers require wine explorer
 * and cannot be run via execShellCommand.
 *
 * Each returned command is a complete guest executable string for one Wine session.
 * The caller chains them via termination callbacks, launching the game after the last one.
 *
 * Completion is tracked via marker files in the game directory (not container config),
 * so importing a container config won't incorrectly skip pre-install steps.
 */
object PreInstallSteps {

    /** Windows path -> installer args, checked against host filesystem to see which exist. */
    private val vcRedistMap: Map<String, String> = mapOf(
        "A:\\_CommonRedist\\vcredist\\2005\\vcredist_x86.exe" to "/Q",
        "A:\\_CommonRedist\\vcredist\\2005\\vcredist_x64.exe" to "/Q",
        "A:\\_CommonRedist\\vcredist\\2008\\vcredist_x86.exe" to "/qb!",
        "A:\\_CommonRedist\\vcredist\\2008\\vcredist_x64.exe" to "/qb!",
        "A:\\_CommonRedist\\vcredist\\2010\\vcredist_x86.exe" to "/passive /norestart",
        "A:\\_CommonRedist\\vcredist\\2010\\vcredist_x64.exe" to "/passive /norestart",
        "A:\\_CommonRedist\\vcredist\\2012\\vcredist_x86.exe" to "/passive /norestart",
        "A:\\_CommonRedist\\vcredist\\2012\\vcredist_x64.exe" to "/passive /norestart",
        "A:\\_CommonRedist\\vcredist\\2013\\vcredist_x86.exe" to "/install /passive /norestart",
        "A:\\_CommonRedist\\vcredist\\2013\\vcredist_x64.exe" to "/install /passive /norestart",
        "A:\\_CommonRedist\\vcredist\\2015\\vc_redist.x86.exe" to "/install /passive /norestart",
        "A:\\_CommonRedist\\vcredist\\2015\\vc_redist.x64.exe" to "/install /passive /norestart",
        "A:\\_CommonRedist\\vcredist\\2017\\vc_redist.x86.exe" to "/install /passive /norestart",
        "A:\\_CommonRedist\\vcredist\\2017\\vc_redist.x64.exe" to "/install /passive /norestart",
        "A:\\_CommonRedist\\vcredist\\2019\\vc_redist.x86.exe" to "/install /passive /norestart",
        "A:\\_CommonRedist\\vcredist\\2019\\vc_redist.x64.exe" to "/install /passive /norestart",
        "A:\\redist\\vcredist_x86.exe" to "",
        "A:\\redist\\vcredist_x64.exe" to "",
        "A:\\_CommonRedist\\MSVC2013\\vcredist_x86.exe" to "/install /passive /norestart",
        "A:\\_CommonRedist\\MSVC2013_x64\\vcredist_x64.exe" to "/install /passive /norestart",
        "A:\\_CommonRedist\\MSVC2015\\VC_redist.x86.exe" to "/install /passive /norestart",
        "A:\\_CommonRedist\\MSVC2015_x64\\VC_redist.x64.exe" to "/install /passive /norestart",
        "A:\\_CommonRedist\\MSVC2017\\VC_redist.x86.exe" to "/install /passive /norestart",
        "A:\\_CommonRedist\\MSVC2017_x64\\VC_redist.x64.exe" to "/install /passive /norestart",
        "A:\\_CommonRedist\\MSVC2019\\VC_redist.x86.exe" to "/install /passive /norestart",
        "A:\\_CommonRedist\\MSVC2019_x64\\VC_redist.x64.exe" to "/install /passive /norestart",
        "A:\\_CommonRedist\\VC_redist.x86.exe" to "/install /passive /norestart",
        "A:\\_CommonRedist\\VC_redist.x64.exe" to "/install /passive /norestart",
    )

    private val allMarkers = listOf(Marker.VCREDIST_INSTALLED, Marker.GOG_SCRIPT_INSTALLED)

    /**
     * Returns a list of guest executable commands for pre-install steps. Each entry is a
     * separate Wine session. Returns empty list if nothing needs installing.
     */
    fun getPreInstallCommands(
        container: Container,
        appId: String,
        gameSource: GameSource,
        screenInfo: String,
        containerVariantChanged: Boolean,
    ): List<String> {
        val gameDir = getGameDir(container) ?: return emptyList()
        val gameDirPath = gameDir.absolutePath

        if (containerVariantChanged) resetMarkers(gameDirPath)

        val commands = mutableListOf<String>()

        if (!MarkerUtils.hasMarker(gameDirPath, Marker.VCREDIST_INSTALLED)) {
            buildVcRedistCommand(gameDir)?.let {
                commands.add(wrapAsGuestExecutable(it, screenInfo))
            }
        }

        if (gameSource == GameSource.GOG &&
            container.containerVariant.equals(Container.GLIBC) &&
            !MarkerUtils.hasMarker(gameDirPath, Marker.GOG_SCRIPT_INSTALLED)
        ) {
            buildGogScriptCommand(appId)?.let {
                commands.add(wrapAsGuestExecutable(it, screenInfo))
            }
        }

        return commands
    }

    fun markAllDone(container: Container) {
        val gameDir = getGameDir(container) ?: return
        val gameDirPath = gameDir.absolutePath
        for (marker in allMarkers) {
            MarkerUtils.addMarker(gameDirPath, marker)
        }
    }

    private fun resetMarkers(gameDirPath: String) {
        for (marker in allMarkers) {
            MarkerUtils.removeMarker(gameDirPath, marker)
        }
    }

    private fun wrapAsGuestExecutable(cmdChain: String, screenInfo: String): String {
        val wrapped = "winhandler.exe cmd /c \"$cmdChain & taskkill /F /IM explorer.exe\""
        return "wine explorer /desktop=shell,$screenInfo $wrapped"
    }

    private fun buildVcRedistCommand(gameDir: File): String? {
        val parts = mutableListOf<String>()
        for ((winPath, args) in vcRedistMap) {
            if (winPath.length < 4 || winPath[1] != ':' || winPath[2] != '\\') continue
            val rest = winPath.substring(3)
            val lastSep = rest.lastIndexOf('\\')
            if (lastSep < 0) continue
            val hostFile = File(gameDir, rest.replace('\\', '/'))
            if (!hostFile.isFile) continue
            parts.add(if (args.isEmpty()) winPath else "$winPath $args")
        }
        return if (parts.isEmpty()) null else parts.joinToString(" & ")
    }

    private fun buildGogScriptCommand(appId: String): String? {
        val parts = GOGService.getInstance()?.gogManager
            ?.getScriptInterpreterPartsForLaunch(appId) ?: return null
        return if (parts.isEmpty()) null else parts.joinToString(" & ")
    }

    private fun getGameDir(container: Container): File? {
        for (drive in Container.drivesIterator(container.drives)) {
            if (drive[0].equals("A", ignoreCase = true)) return File(drive[1])
        }
        return null
    }
}
