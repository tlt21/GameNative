package com.winlator.fexcore;

import app.gamenative.R;

import android.content.Context;
import com.winlator.container.Shortcut;

import android.widget.ArrayAdapter;
import android.widget.Spinner;

import com.winlator.container.Container;
import com.winlator.contents.ContentProfile;
import com.winlator.contents.ContentsManager;
import com.winlator.core.AppUtils;
import com.winlator.core.DefaultVersion;
import com.winlator.core.FileUtils;
import com.winlator.xenvironment.ImageFs;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import timber.log.Timber;

public final class FEXCoreManager {

    /**
     * FEX AppConfig overrides. Each entry pairs a case-insensitive substring matched
     * against the Wine command line with the exe basename FEX uses to key its
     * per-app config. Most entries are a plain "foo.exe" which does both jobs, but
     * entries like the Rockstar Launcher need a disambiguating path so we don't
     * accidentally target every game's "Launcher.exe".
     */
    private static final AppConfigOverride[] APP_CONFIG_OVERRIDES = {
        new AppConfigOverride("RockstarService.exe", "RockstarService.exe"),
        new AppConfigOverride("RockstarSteamHelper.exe", "RockstarSteamHelper.exe"),
        new AppConfigOverride("SocialClubHelper.exe", "SocialClubHelper.exe"),
        new AppConfigOverride("UplayWebCore.exe", "UplayWebCore.exe"),
        new AppConfigOverride("steamservice.exe", "steamservice.exe"),
        new AppConfigOverride("steamwebhelper.exe", "steamwebhelper.exe"),
        new AppConfigOverride("steam.exe", "steam.exe"),
        // Scope match to the Rockstar Launcher install folder so game-specific "Launcher.exe"
        // binaries don't silently pick up Rockstar's FEX tuning.
        new AppConfigOverride("Rockstar Games\\Launcher\\Launcher.exe", "Launcher.exe"),
    };

    private static final class AppConfigOverride {
        final String matchSubstring;
        final String exeBasename;

        AppConfigOverride(String matchSubstring, String exeBasename) {
            this.matchSubstring = matchSubstring;
            this.exeBasename = exeBasename;
        }
    }

    private static final String APP_CONFIG_CONTENT = "{\n" +
        "  \"Config\": {\n" +
        "    \"Multiblock\": \"0\",\n" +
        "    \"X87ReducedPrecision\": \"1\",\n" +
        "    \"VectorTSOEnabled\": \"1\",\n" +
        "    \"HalfBarrierTSOEnabled\": \"1\",\n" +
        "    \"MonoHacks\": \"0\"\n" +
        "  }\n" +
        "}\n";

    FEXCoreManager() {
    }

    public static File ensureAppConfigOverrides(Context context) {
        try {
            ImageFs imageFs = ImageFs.find(context);
            File rootDir = imageFs.getRootDir();
            File baseDir = new File(rootDir, "/home/xuser/.fex-emu");
            File appConfigDir = new File(baseDir, "AppConfig");

            // FEX keys AppConfig by exe basename; dedupe so multiple match substrings
            // pointing at the same exe don't rewrite the same file.
            Set<String> writtenBasenames = new LinkedHashSet<>();
            for (AppConfigOverride override : APP_CONFIG_OVERRIDES) {
                if (!writtenBasenames.add(override.exeBasename)) continue;
                File appConfigFile = new File(appConfigDir, override.exeBasename + ".json");
                FileUtils.writeString(appConfigFile, APP_CONFIG_CONTENT);
            }

            return baseDir;
        } catch (Exception e) {
            Timber.e(e, "Failed to write FEX app config overrides");
            return null;
        }
    }

    public static String findAppConfigExeName(String commandLine) {
        if (commandLine == null || commandLine.isEmpty()) return null;
        String lowerCommand = commandLine.toLowerCase(Locale.ENGLISH);
        for (AppConfigOverride override : APP_CONFIG_OVERRIDES) {
            if (lowerCommand.contains(override.matchSubstring.toLowerCase(Locale.ENGLISH))) {
                return override.exeBasename;
            }
        }
        return null;
    }

    /**
     * Delete existing FEXCore config files for the given container.
     * Removes both container-level config files and app-level config directories.
     */
    public static void deleteConfigFiles(Context context, String containerId) {
        try {
            ImageFs imageFs = ImageFs.find(context);
            // Delete container-level config
            File containerConfig = new File(imageFs.home_path + "-" + containerId + "/.fex-emu/Config.json");
            if (containerConfig.exists()) {
                containerConfig.delete();
            }
            // Delete app-level config directory
            File appConfigDir = new File(context.getFilesDir(), "imagefs/home/xuser/.fex-emu/AppConfig");
            if (appConfigDir.exists() && appConfigDir.isDirectory()) {
                FileUtils.delete(appConfigDir);
            }
        } catch (Exception e) {
            Timber.e(e, "Failed to delete FEXCore config files");
        }
    }

    public static void loadFEXCoreVersion(Context context, ContentsManager contentsManager, Spinner spinner, Container container) {
        String[] originalItems = context.getResources().getStringArray(R.array.fexcore_version_entries);
        List<String> itemList = new ArrayList<>(Arrays.asList(originalItems));
        for (ContentProfile profile : contentsManager.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_FEXCORE)) {
            String entryName = ContentsManager.getEntryName(profile);
            int firstDashIndex = entryName.indexOf('-');
            itemList.add(entryName.substring(firstDashIndex + 1));
        }
        spinner.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item, itemList));
        if (container != null)
            AppUtils.setSpinnerSelectionFromValue(spinner, container.getFEXCoreVersion());
        else
            AppUtils.setSpinnerSelectionFromValue(spinner, DefaultVersion.FEXCORE);
    }

    public static void loadFEXCoreVersion(Context context, ContentsManager contentsManager, Spinner spinner, Shortcut shortcut) {
        String[] originalItems = context.getResources().getStringArray(R.array.fexcore_version_entries);
        List<String> itemList = new ArrayList<>(Arrays.asList(originalItems));
        for (ContentProfile profile : contentsManager.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_FEXCORE)) {
            String entryName = ContentsManager.getEntryName(profile);
            int firstDashIndex = entryName.indexOf('-');
            itemList.add(entryName.substring(firstDashIndex + 1));
        }
        spinner.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item, itemList));
        AppUtils.setSpinnerSelectionFromValue(spinner, shortcut.getExtra("fexcoreVersion", shortcut.container.getFEXCoreVersion()));
    }
}
