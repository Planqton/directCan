package at.planqton.directcan.util

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

object LocaleHelper {

    fun setLocale(context: Context, languageCode: String): Context {
        val locale = when (languageCode) {
            "en" -> Locale.ENGLISH
            "de" -> Locale.GERMAN
            else -> getSystemLocale()
        }

        Locale.setDefault(locale)

        val config = Configuration(context.resources.configuration)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.setLocales(LocaleList(locale))
        } else {
            @Suppress("DEPRECATION")
            config.locale = locale
        }

        return context.createConfigurationContext(config)
    }

    private fun getSystemLocale(): Locale {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            LocaleList.getDefault()[0]
        } else {
            @Suppress("DEPRECATION")
            Locale.getDefault()
        }
    }

    fun getLanguageDisplayName(languageCode: String, context: Context): String {
        return when (languageCode) {
            "system" -> context.getString(at.planqton.directcan.R.string.language_system)
            "en" -> context.getString(at.planqton.directcan.R.string.language_en)
            "de" -> context.getString(at.planqton.directcan.R.string.language_de)
            else -> languageCode
        }
    }
}
