package ru.rollers.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import ru.rollers.core.api.AuthResponse

/** Токен и данные текущего пользователя (хранятся в SharedPreferences). */
class Session(context: Context) {
    private val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)

    private val _user = MutableStateFlow(
        prefs.getString("token", null)?.let { AuthResponse(it, prefs.getLong("uid", 0), prefs.getString("name", "") ?: "") }
    )
    val user: StateFlow<AuthResponse?> = _user
    val token: String? get() = _user.value?.token

    fun save(a: AuthResponse) {
        prefs.edit().putString("token", a.token).putLong("uid", a.userId).putString("name", a.name).apply()
        _user.value = a
    }

    fun logout() {
        prefs.edit().clear().apply()
        _user.value = null
    }
}
