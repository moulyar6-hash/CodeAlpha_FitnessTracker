package com.example.fitnesstracker

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

private val AppGreen = Color(0xFFB9E769)
private val AppDark = Color(0xFF101510)
private val AppCard = Color(0xFF202820)
private val AppMuted = Color(0xFF9DA99A)
private val AppWhite = Color(0xFFF4F7F0)

private fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

private data class FitnessEntry(
    val id: Long,
    val date: String,
    val steps: Int,
    val workout: String,
    val minutes: Int,
    val calories: Int
)

private class FitnessDatabase(context: Context) :
    SQLiteOpenHelper(context, "fitness_tracker.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS fitness_entries (id INTEGER PRIMARY KEY AUTOINCREMENT, date TEXT, steps INTEGER, workout TEXT, minutes INTEGER, calories INTEGER)")
        createExtraTables(db)
    }

    private fun createExtraTables(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS water_logs (id INTEGER PRIMARY KEY AUTOINCREMENT, date TEXT NOT NULL, amount INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS app_settings (setting_key TEXT PRIMARY KEY, setting_value TEXT)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createExtraTables(db)
    }

    fun allEntries(): List<FitnessEntry> {
        val result = mutableListOf<FitnessEntry>()
        readableDatabase.rawQuery("SELECT id,date,steps,workout,minutes,calories FROM fitness_entries ORDER BY id DESC", null).use { c ->
            while (c.moveToNext()) {
                result.add(FitnessEntry(c.getLong(0), c.getString(1) ?: today(), c.getInt(2), c.getString(3) ?: "Walking", c.getInt(4), c.getInt(5)))
            }
        }
        return result
    }

    fun insertEntry(entry: FitnessEntry) {
        val values = ContentValues().apply {
            put("date", entry.date); put("steps", entry.steps); put("workout", entry.workout)
            put("minutes", entry.minutes); put("calories", entry.calories)
        }
        writableDatabase.insert("fitness_entries", null, values)
    }

    fun deleteEntry(id: Long) {
        writableDatabase.delete("fitness_entries", "id=?", arrayOf(id.toString()))
    }

    fun waterToday(): Int {
        var total = 0
        readableDatabase.rawQuery("SELECT COALESCE(SUM(amount),0) FROM water_logs WHERE date=?", arrayOf(today())).use { c ->
            if (c.moveToFirst()) total = c.getInt(0)
        }
        return total
    }

    fun addWater(amount: Int) {
        val values = ContentValues().apply { put("date", today()); put("amount", amount) }
        writableDatabase.insert("water_logs", null, values)
    }

    fun setting(key: String, fallback: String): String {
        readableDatabase.rawQuery("SELECT setting_value FROM app_settings WHERE setting_key=?", arrayOf(key)).use { c ->
            if (c.moveToFirst()) return c.getString(0) ?: fallback
        }
        return fallback
    }

    fun saveSetting(key: String, value: String) {
        val values = ContentValues().apply { put("setting_key", key); put("setting_value", value) }
        writableDatabase.insertWithOnConflict("app_settings", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }
}

private enum class Screen { HOME, LOG, INSIGHTS, PROFILE }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { FitLifeApp() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FitLifeApp() {
    val context = LocalContext.current
    val db = remember { FitnessDatabase(context.applicationContext) }
    var entries by remember { mutableStateOf(db.allEntries()) }
    val csvLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { writer ->
                    writer.appendLine("Date,Workout,Steps,Minutes,Calories")
                    entries.forEach { entry ->
                        val safeWorkout = entry.workout.replace("\"", "\"\"")
                        writer.appendLine("${entry.date},\"$safeWorkout\",${entry.steps},${entry.minutes},${entry.calories}")
                    }
                }
                Toast.makeText(context, "Fitness report exported", Toast.LENGTH_SHORT).show()
            } catch (error: Exception) {
                Toast.makeText(context, "Export failed: ${error.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
    var water by remember { mutableIntStateOf(db.waterToday()) }
    var screen by remember { mutableStateOf(Screen.HOME) }
    var name by remember { mutableStateOf(db.setting("name", "")) }
    var stepGoalText by remember { mutableStateOf(db.setting("step_goal", "8000")) }
    var waterGoalText by remember { mutableStateOf(db.setting("water_goal", "2000")) }
    var heightText by remember { mutableStateOf(db.setting("height", "")) }
    var weightText by remember { mutableStateOf(db.setting("weight", "")) }
    var darkMode by remember { mutableStateOf(db.setting("dark_mode", "true").toBoolean()) }
    var waterAmountText by remember { mutableStateOf("250") }
    var timerSeconds by remember { mutableIntStateOf(0) }
    var timerRunning by remember { mutableStateOf(false) }

    LaunchedEffect(timerRunning) {
        while (timerRunning) {
            delay(1000)
            timerSeconds++
        }
    }
    var stepsText by remember { mutableStateOf("") }
    var minutesText by remember { mutableStateOf("") }
    var caloriesText by remember { mutableStateOf("") }
    var workout by remember { mutableStateOf("Walking") }
    var workoutMenu by remember { mutableStateOf(false) }

    fun refresh() { entries = db.allEntries(); water = db.waterToday() }

    val todayEntries = entries.filter { it.date == today() }
    val steps = todayEntries.sumOf { it.steps }
    val minutes = todayEntries.sumOf { it.minutes }
    val calories = todayEntries.sumOf { it.calories }
    val stepGoal = stepGoalText.toIntOrNull()?.coerceAtLeast(1) ?: 8000
    val waterGoal = waterGoalText.toIntOrNull()?.coerceAtLeast(1) ?: 2000
    val height = heightText.toDoubleOrNull()?.div(100.0)
    val weight = weightText.toDoubleOrNull()
    val bmi = if (height != null && weight != null && height > 0) weight / (height * height) else null

    val scheme = if (darkMode) {
        darkColorScheme(primary = AppGreen, onPrimary = AppDark, background = AppDark, surface = AppCard, onSurface = AppWhite, onBackground = AppWhite, secondary = AppGreen)
    } else {
        lightColorScheme(primary = Color(0xFF496B18), onPrimary = Color.White, background = Color(0xFFF4F7F0), surface = Color.White, onSurface = Color(0xFF182018), onBackground = Color(0xFF182018), secondary = Color(0xFF496B18))
    }
    MaterialTheme(colorScheme = scheme) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    title = { Column {
                        Text("FITLIFE", color = AppGreen, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 3.sp)
                        Text(if (name.isBlank()) "Fitness Tracker" else "Hello, $name", color = AppWhite, fontWeight = FontWeight.Bold)
                    } },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
                )
            },
            bottomBar = {
                NavigationBar(containerColor = AppCard) {
                    NavigationBarItem(selected = screen == Screen.HOME, onClick = { screen = Screen.HOME }, icon = { Text("⌂") }, label = { Text("Home") })
                    NavigationBarItem(selected = screen == Screen.LOG, onClick = { screen = Screen.LOG }, icon = { Text("＋") }, label = { Text("Log") })
                    NavigationBarItem(selected = screen == Screen.INSIGHTS, onClick = { screen = Screen.INSIGHTS }, icon = { Text("▥") }, label = { Text("Insights") })
                    NavigationBarItem(selected = screen == Screen.PROFILE, onClick = { screen = Screen.PROFILE }, icon = { Text("○") }, label = { Text("Profile") })
                }
            }
        ) { padding ->
            when (screen) {
                Screen.HOME -> LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    item { CardBlock {
                        Text("DAILY STEPS", color = AppMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text("%,d".format(steps), fontSize = 38.sp, fontWeight = FontWeight.ExtraBold, color = AppWhite)
                        Text("Goal: %,d steps".format(stepGoal), color = AppMuted)
                        LinearProgressIndicator(progress = (steps.toFloat() / stepGoal).coerceIn(0f, 1f), modifier = Modifier.fillMaxWidth().height(8.dp), color = AppGreen, trackColor = Color(0xFF3A4438))
                        Text("${(steps * 100 / stepGoal)}% completed", color = AppGreen, fontWeight = FontWeight.Bold)
                    } }
                    item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        MetricCard("Calories", calories.toString(), "kcal", Modifier.weight(1f))
                        MetricCard("Workout", minutes.toString(), "minutes", Modifier.weight(1f))
                    } }
                    item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        MetricCard("Water", water.toString(), "ml today", Modifier.weight(1f))
                        MetricCard("Sessions", todayEntries.size.toString(), "today", Modifier.weight(1f))
                    } }
                    item { CardBlock {
                        Text("Water intake", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text("$water / $waterGoal ml", color = AppGreen)
                        LinearProgressIndicator(progress = (water.toFloat() / waterGoal).coerceIn(0f, 1f), modifier = Modifier.fillMaxWidth(), color = AppGreen)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { db.addWater(250); refresh() }, modifier = Modifier.weight(1f)) { Text("+250 ml") }
                            Button(onClick = { db.addWater(500); refresh() }, modifier = Modifier.weight(1f)) { Text("+500 ml") }
                        }
                    } }
                    item { Text("Recent activity", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = AppWhite) }
                    if (entries.isEmpty()) item { Text("No activity yet. Use Log to add your first workout.", color = AppMuted) }
                    items(entries.take(5), key = { it.id }) { entry -> EntryCard(entry) { db.deleteEntry(entry.id); refresh() } }
                }
                Screen.LOG -> LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { Text("Log an activity", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = AppWhite) }
                    item {
                        CardBlock {
                            ExposedDropdownMenuBox(expanded = workoutMenu, onExpandedChange = { workoutMenu = !workoutMenu }) {
                                OutlinedTextField(value = workout, onValueChange = {}, readOnly = true, label = { Text("Workout type") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = workoutMenu) }, modifier = Modifier.menuAnchor().fillMaxWidth())
                                ExposedDropdownMenu(expanded = workoutMenu, onDismissRequest = { workoutMenu = false }) {
                                    listOf("Walking", "Running", "Cycling", "Gym", "Yoga", "Swimming", "Sports", "Other").forEach { option ->
                                        DropdownMenuItem(text = { Text(option) }, onClick = { workout = option; workoutMenu = false })
                                    }
                                }
                            }
                            NumericField("Steps", stepsText) { stepsText = it }
                            NumericField("Duration in minutes", minutesText) { minutesText = it }
                            NumericField("Calories burned", caloriesText) { caloriesText = it }
                            Text("Workout timer", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = AppWhite)
                            CardBlock {
                                Text(String.format(Locale.US, "%02d:%02d:%02d", timerSeconds / 3600, (timerSeconds / 60) % 60, timerSeconds % 60), fontSize = 32.sp, fontWeight = FontWeight.Bold, color = AppGreen)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { timerRunning = !timerRunning }, modifier = Modifier.weight(1f)) { Text(if (timerRunning) "Pause" else "Start") }
                                    OutlinedButton(onClick = { timerRunning = false; timerSeconds = 0 }, modifier = Modifier.weight(1f)) { Text("Reset") }
                                }
                                Text("The timer measures your session; save activity details separately.", color = AppMuted, fontSize = 12.sp)
                            }
                            CardBlock {
                                Button(onClick = {
                                    val s = stepsText.toIntOrNull() ?: 0
                                    val m = minutesText.toIntOrNull() ?: 0
                                    val c = caloriesText.toIntOrNull() ?: 0
                                    if (s < 0 || m < 0 || c < 0 || (stepsText.isBlank() && minutesText.isBlank() && caloriesText.isBlank())) {
                                        Toast.makeText(context, "Enter valid activity values", Toast.LENGTH_SHORT).show()
                                    } else {
                                        db.insertEntry(FitnessEntry(0, today(), s, workout, m, c))
                                        stepsText = ""; minutesText = ""; caloriesText = ""; refresh()
                                        Toast.makeText(context, "Activity saved", Toast.LENGTH_SHORT).show()
                                        screen = Screen.HOME
                                    }
                                }, modifier = Modifier.fillMaxWidth()) { Text("Save activity") }
                            }
                        }
                    }
                }
                Screen.INSIGHTS -> LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { Text("Your insights", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = AppWhite) }
                    item { MetricCard("All-time steps", "%,d".format(entries.sumOf { it.steps }), "steps", Modifier.fillMaxWidth()) }
                    item { MetricCard("All-time workouts", entries.size.toString(), "sessions", Modifier.fillMaxWidth()) }
                    item { MetricCard("Total workout time", entries.sumOf { it.minutes }.toString(), "minutes", Modifier.fillMaxWidth()) }
                    item { CardBlock {
                        Text("Last 7 days", fontSize = 19.sp, fontWeight = FontWeight.Bold)
                        (6 downTo 0).forEach { offset ->
                            val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(System.currentTimeMillis() - offset * 86_400_000L))
                            val daySteps = entries.filter { it.date == date }.sumOf { it.steps }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(date.takeLast(5), color = AppMuted, modifier = Modifier.width(48.dp), fontSize = 12.sp)
                                LinearProgressIndicator(progress = (daySteps.toFloat() / stepGoal).coerceIn(0f, 1f), modifier = Modifier.weight(1f).height(8.dp), color = AppGreen)
                                Text(daySteps.toString(), fontSize = 12.sp)
                            }
                        }
                    } }
                    item {
                        CardBlock {
                            Text("Export your progress", fontSize = 19.sp, fontWeight = FontWeight.Bold)
                            Text("Save your logged workouts as a CSV file that you can open in Excel or Google Sheets.", color = AppMuted)
                            Button(
                                onClick = { csvLauncher.launch("fitlife_report.csv") },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Export CSV report") }
                        }
                    }
                    item { Text("Activity history", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = AppWhite) }
                    if (entries.isEmpty()) item { Text("Your saved activities will appear here.", color = AppMuted) }
                    items(entries, key = { it.id }) { entry -> EntryCard(entry) { db.deleteEntry(entry.id); refresh() } }
                }
                Screen.PROFILE -> LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { Text("Profile & goals", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = AppWhite) }
                    item { CardBlock {
                        OutlinedTextField(value = name, onValueChange = { name = it; db.saveSetting("name", it) }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        NumericField("Daily step goal", stepGoalText) { stepGoalText = it; db.saveSetting("step_goal", it) }
                        NumericField("Daily water goal (ml)", waterGoalText) { waterGoalText = it; db.saveSetting("water_goal", it) }
                        NumericField("Add water amount (ml)", waterAmountText) { waterAmountText = it }
                        Button(onClick = {
                            val amount = waterAmountText.toIntOrNull()
                            if (amount == null || amount <= 0) Toast.makeText(context, "Enter a valid amount", Toast.LENGTH_SHORT).show()
                            else { db.addWater(amount); refresh(); Toast.makeText(context, "Water added", Toast.LENGTH_SHORT).show() }
                        }, modifier = Modifier.fillMaxWidth()) { Text("Add custom water amount") }
                    } }
                    item { CardBlock {
                        Text("Appearance", fontSize = 19.sp, fontWeight = FontWeight.Bold)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                            Text(if (darkMode) "Dark mode" else "Light mode")
                            Switch(checked = darkMode, onCheckedChange = { darkMode = it; db.saveSetting("dark_mode", it.toString()) })
                        }
                    } }
                    item { CardBlock {
                        Text("BMI calculator", fontSize = 19.sp, fontWeight = FontWeight.Bold)
                        DecimalField("Height (cm)", heightText) { heightText = it; db.saveSetting("height", it) }
                        DecimalField("Weight (kg)", weightText) { weightText = it; db.saveSetting("weight", it) }
                        if (bmi != null) Text("Your BMI: %.1f".format(bmi), color = AppGreen, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Text("BMI is a screening measure, not a diagnosis.", color = AppMuted, fontSize = 12.sp)
                    } }
                }
            }
        }
    }
}

@Composable
private fun CardBlock(content: @Composable ColumnScope.() -> Unit) {
    Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = AppCard), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
private fun MetricCard(title: String, value: String, unit: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier, shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = AppCard)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title.uppercase(), color = AppMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text(value, color = AppWhite, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text(unit, color = AppGreen, fontSize = 12.sp)
        }
    }
}

@Composable
private fun NumericField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = { input -> if (input.all { it.isDigit() } || input.isEmpty()) onValueChange(input) }, label = { Text(label) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun DecimalField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = { input -> if (input.matches(Regex("^\\d*\\.?\\d*$"))) onValueChange(input) }, label = { Text(label) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun EntryCard(entry: FitnessEntry, onDelete: () -> Unit) {
    Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = AppCard), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(entry.workout, color = AppWhite, fontWeight = FontWeight.Bold)
                Text(entry.date, color = AppMuted, fontSize = 12.sp)
                Text("${entry.steps} steps • ${entry.minutes} min • ${entry.calories} kcal", color = AppMuted, fontSize = 12.sp)
            }
            TextButton(onClick = onDelete) { Text("Delete", color = AppGreen) }
        }
    }
}
