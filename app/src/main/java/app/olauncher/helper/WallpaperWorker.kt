package app.olauncher.helper

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.olauncher.data.Prefs
import kotlinx.coroutines.coroutineScope

class WallpaperWorker(appContext: Context, workerParams: WorkerParameters) : CoroutineWorker(appContext, workerParams) {

    private val prefs = Prefs(applicationContext)

    override suspend fun doWork(): Result = coroutineScope {
        val success =
            if (prefs.appTheme == AppCompatDelegate.MODE_NIGHT_YES && isOlauncherDefault(applicationContext).not())
                true
            else if (prefs.dailyWallpaper) {
                val (index, wallpaperUrl) = getTodaysWallpaper(applicationContext)
                if (prefs.dailyWallpaperUrl == wallpaperUrl)
                    true
                else if (setWallpaper(applicationContext, index)) {
                    prefs.dailyWallpaperUrl = wallpaperUrl
                    true
                } else false
            } else
                true

        if (success)
            Result.success()
        else
            Result.retry()
    }

}
