package dev.devicelink.designsystem

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AppearanceMode { SYSTEM, LIGHT, DARK }
enum class Accent { OCEAN, IRIS, FOREST }
enum class Corners { SOFT, ROUND, SQUARE }

data class Appearance(
    val mode: AppearanceMode = AppearanceMode.SYSTEM,
    val accent: Accent = Accent.OCEAN,
    val corners: Corners = Corners.SOFT,
    val dynamicColor: Boolean = false,
    val sessionMinutes: Int = 15,
)

/** Runtime design configuration, persisted independently of feature screens. */
class AppearanceStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("appearance", Context.MODE_PRIVATE)
    private inline fun <reified T : Enum<T>> enumValue(key: String, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == prefs.getString(key, null) } ?: fallback
    private val mutable = MutableStateFlow(Appearance(
        mode = enumValue("mode", AppearanceMode.SYSTEM),
        accent = enumValue("accent", Accent.OCEAN),
        corners = enumValue("corners", Corners.SOFT),
        dynamicColor = prefs.getBoolean("dynamic", false),
        sessionMinutes = prefs.getInt("session", 15).takeIf { it in listOf(5, 15, 30) } ?: 15,
    ))
    val appearance = mutable.asStateFlow()

    fun update(value: Appearance) {
        val validated = value.copy(sessionMinutes = value.sessionMinutes.takeIf { it in listOf(5, 15, 30) } ?: 15)
        prefs.edit {
            putString("mode", validated.mode.name)
            putString("accent", validated.accent.name)
            putString("corners", validated.corners.name)
            putBoolean("dynamic", validated.dynamicColor)
            putInt("session", validated.sessionMinutes)
        }
        mutable.value = validated
    }
}
