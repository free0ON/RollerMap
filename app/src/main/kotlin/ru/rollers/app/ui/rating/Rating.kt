package ru.rollers.app.ui.rating

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import ru.rollers.app.RollersApp
import ru.rollers.app.ui.events.readable
import ru.rollers.app.ui.formatTime
import ru.rollers.core.api.ActivitySummary
import ru.rollers.core.api.LoginRequest
import ru.rollers.core.api.Profile
import ru.rollers.core.api.RegisterRequest
import ru.rollers.core.model.SkillLevel
import ru.rollers.core.rating.LeaderboardEntry
import ru.rollers.core.rating.RankMetric

class LeaderboardViewModel : ViewModel() {
    var metric by mutableStateOf(RankMetric.OVERALL); private set
    var entries by mutableStateOf<List<LeaderboardEntry>>(emptyList()); private set
    var error by mutableStateOf<String?>(null)
    fun load(m: RankMetric = metric) = viewModelScope.launch {
        metric = m
        runCatching { entries = RollersApp.instance.api.leaderboard(m) }.onFailure { error = it.readable() }
    }
}

@Composable
fun LeaderboardScreen(vm: LeaderboardViewModel = viewModel()) {
    LaunchedEffect(Unit) { vm.load() }
    val me = RollersApp.instance.session.user.collectAsState().value?.userId
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Text("Рейтинг", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(vertical = 12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(RankMetric.OVERALL to "Общий", RankMetric.MAX_SPEED to "Макс. скорость",
                RankMetric.MAX_AVG_SPEED to "Средняя", RankMetric.DISTANCE to "Дистанция").forEach { (m, t) ->
                FilterChip(vm.metric == m, { vm.load(m) }, label = { Text(t) })
            }
        }
        vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        LazyColumn {
            items(vm.entries) { e ->
                val value = when (vm.metric) {
                    RankMetric.MAX_SPEED, RankMetric.MAX_AVG_SPEED -> "%.1f км/ч".format(e.value)
                    RankMetric.DISTANCE -> "%.0f км".format(e.value / 1000)
                    RankMetric.OVERALL -> "${e.value.toInt()} очк."
                }
                ListItem(
                    leadingContent = { Text("${e.position}", fontWeight = FontWeight.Bold) },
                    headlineContent = { Text(e.stats.name, fontWeight = if (e.stats.userId == me) FontWeight.Bold else null) },
                    supportingContent = { Text("${e.rank.title} · ${e.rank.level.title}") },
                    trailingContent = { Text(value) },
                )
            }
        }
    }
}

// ---------------- Профиль и вход

class ProfileViewModel : ViewModel() {
    private val app = RollersApp.instance
    var profile by mutableStateOf<Profile?>(null); private set
    var activities by mutableStateOf<List<ActivitySummary>>(emptyList()); private set
    var error by mutableStateOf<String?>(null)

    fun load() = viewModelScope.launch {
        if (app.session.token == null) return@launch
        runCatching { profile = app.api.me(); activities = app.api.myActivities() }.onFailure { error = it.readable() }
    }

    fun auth(register: Boolean, name: String, email: String, password: String, level: SkillLevel) = viewModelScope.launch {
        error = null
        runCatching {
            if (register) app.api.register(RegisterRequest(name, email, password, level)) else app.api.login(LoginRequest(email, password))
        }.onSuccess { app.session.save(it); load() }.onFailure { error = it.readable() }
    }

    fun logout() { app.session.logout(); profile = null; activities = emptyList() }
}

@Composable
fun ProfileScreen(vm: ProfileViewModel = viewModel()) {
    val user by RollersApp.instance.session.user.collectAsState()
    LaunchedEffect(user) { vm.load() }
    if (user == null) { LoginForm(vm); return }
    val p = vm.profile
    LazyColumn(Modifier.fillMaxSize().padding(12.dp)) {
        item {
            Text(user!!.name, style = MaterialTheme.typography.headlineSmall)
            if (p != null) {
                Text("${p.rank.title} · ${p.rank.points} очков" + (p.rank.nextRankAt?.let { " (до следующего звания ${it - p.rank.points})" } ?: ""))
                Text("Уровень: ${p.level.title}")
                Spacer(Modifier.height(8.dp))
                Text("Максимальная скорость: %.1f км/ч".format(p.stats.maxSpeedKmh))
                Text("Лучшая средняя скорость: %.1f км/ч".format(p.stats.maxAvgSpeedKmh))
                Text("Всего пройдено: %.1f км за %d заездов".format(p.stats.totalDistanceM / 1000, p.stats.activities))
            }
            vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text("Заезды", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
        }
        items(vm.activities) { a ->
            ListItem(
                headlineContent = { Text("%.2f км · %.1f км/ч".format(a.stats.distanceM / 1000, a.stats.avgMovingSpeedKmh)) },
                supportingContent = { Text("${formatTime(a.createdAt, withDate = true)} · макс. %.1f км/ч · %d мин".format(a.stats.maxSpeedKmh, a.stats.movingMs / 60000)) },
            )
        }
        item { OutlinedButton(vm::logout, Modifier.padding(top = 12.dp)) { Text("Выйти") } }
    }
}

@Composable
private fun LoginForm(vm: ProfileViewModel) {
    var register by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var level by remember { mutableStateOf(SkillLevel.BEGINNER) }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (register) "Регистрация" else "Вход", style = MaterialTheme.typography.headlineSmall)
        if (register) OutlinedTextField(name, { name = it }, label = { Text("Имя") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(email, { email = it }, label = { Text("Email") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(password, { password = it }, label = { Text("Пароль") }, modifier = Modifier.fillMaxWidth(),
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
        if (register) {
            Text("Ваш уровень катания")
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                SkillLevel.entries.forEach { FilterChip(level == it, { level = it }, label = { Text(it.title) }) }
            }
        }
        vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button({ vm.auth(register, name, email, password, level) }, Modifier.fillMaxWidth()) { Text(if (register) "Зарегистрироваться" else "Войти") }
        TextButton({ register = !register }) { Text(if (register) "Уже есть аккаунт" else "Создать аккаунт") }
    }
}
