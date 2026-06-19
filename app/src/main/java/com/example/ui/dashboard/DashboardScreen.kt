package com.example.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object UiColors {
    val Background = Color(0xFF0F111A)
    val Surface = Color(0xFF1B1D26)
    val SurfaceLight = Color(0xFF262833)
    val TextPrimary = Color(0xFFFFFFFF)
    val TextSecondary = Color(0xFFA1A3AB)
    val PrimaryYellow = Color(0xFFFFC107)
    val BearishRed = Color(0xFFFF5252)
    val BullishGreen = Color(0xFF4ADE80)
    val Cyan = Color(0xFF22D3EE)
    val WaitGray = Color(0xFF6B7280)
}

data class SessionInfo(val name: String, val status: String, val utcRange: String, val wibRange: String, val active: Boolean)
data class ConceptInfo(val title: String, val icon: String, val status: String, val timeframe: String, val value: String)

@Composable
fun rememberLiveClock(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    return now
}

@Composable
fun DashboardScreen(viewModel: TradingBotViewModel = viewModel()) {
    var currentTab by remember { mutableStateOf("Dashboard") }
    val now = rememberLiveClock()
    val session = currentSessionInfo(now)

    LaunchedEffect(Unit) {
        if (viewModel.settings.areKeysSet()) viewModel.startBot() else viewModel.log("Menunggu TwelveData API Key di Settings.")
    }

    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = UiColors.Surface, contentColor = UiColors.TextSecondary) {
                NavigationBarItem(selected = currentTab == "Dashboard", onClick = { currentTab = "Dashboard" }, icon = { Icon(Icons.Default.GridView, null) }, label = { Text("Dashboard") }, colors = navColors())
                NavigationBarItem(selected = currentTab == "Analyze", onClick = { currentTab = "Analyze" }, icon = { Icon(Icons.Default.GpsFixed, null) }, label = { Text("ICT Analyze") }, colors = navColors())
                NavigationBarItem(selected = currentTab == "Journal", onClick = { currentTab = "Journal" }, icon = { Icon(Icons.Default.History, null) }, label = { Text("History") }, colors = navColors())
                NavigationBarItem(selected = currentTab == "Terminal", onClick = { currentTab = "Terminal" }, icon = { Icon(Icons.Default.Terminal, null) }, label = { Text("Terminal") }, colors = navColors())
                NavigationBarItem(selected = currentTab == "Settings", onClick = { currentTab = "Settings" }, icon = { Icon(Icons.Default.Settings, null) }, label = { Text("Settings") }, colors = navColors())
            }
        },
        containerColor = UiColors.Background
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            TopBar()
            HorizontalDivider(color = UiColors.SurfaceLight)
            when (currentTab) {
                "Dashboard" -> DashboardTab(viewModel, session, now, onNavigateAnalyze = { currentTab = "Analyze" })
                "Analyze" -> AnalyzeTab(viewModel, session)
                "Journal" -> JournalTab(viewModel)
                "Terminal" -> TerminalTab(viewModel)
                "Settings" -> SettingsTab(viewModel)
            }
        }
    }
}

@Composable
private fun navColors() = NavigationBarItemDefaults.colors(
    selectedIconColor = UiColors.PrimaryYellow,
    unselectedIconColor = UiColors.TextSecondary,
    selectedTextColor = UiColors.PrimaryYellow,
    unselectedTextColor = UiColors.TextSecondary,
    indicatorColor = Color.Transparent
)

@Composable
fun TopBar() {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(UiColors.PrimaryYellow).padding(horizontal = 8.dp, vertical = 5.dp), contentAlignment = Alignment.Center) {
                Text("XAU", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text("XAUUSD ICT", color = UiColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text("Inner Circle Trader", color = UiColors.PrimaryYellow, fontSize = 11.sp)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(7.dp).background(UiColors.BullishGreen, CircleShape))
            Spacer(modifier = Modifier.width(5.dp))
            Text("Live", color = UiColors.TextSecondary, fontSize = 11.sp)
        }
    }
}

// ─────────────── DASHBOARD TAB ───────────────

@Composable
fun DashboardTab(viewModel: TradingBotViewModel, session: SessionInfo, now: Long, onNavigateAnalyze: () -> Unit) {
    val analyses by viewModel.ictAnalyses.collectAsState()
    val latest = analyses.firstOrNull()
    val json = latest?.rawResult?.let { parseJson(it) }
    val concepts = buildConcepts(json, latest?.timeframe ?: "-", session)
    val activeConcepts = concepts.count { it.status == "ACTIVE" }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Spacer(Modifier.height(4.dp)) }
        item { HeaderMarketCard(session, now) }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricCard(Modifier.weight(1f), analyses.size.toString(), "Analyses", UiColors.PrimaryYellow)
                MetricCard(Modifier.weight(1f), analyses.count { it.bias.equals("BULLISH", true) }.toString(), "Bullish", UiColors.BullishGreen)
                MetricCard(Modifier.weight(1f), analyses.count { it.bias.equals("BEARISH", true) }.toString(), "Bearish", UiColors.BearishRed)
            }
        }
        item {
            ActionCard("Analisis ICT Sekarang", "Rule engine membaca candle real-time dan market events", Icons.Default.FlashOn) { onNavigateAnalyze() }
        }
        item { SessionsCard(session, now) }
        item { ConceptsCoveredCard(concepts, activeConcepts) }
        item { RecentAnalysesCard(analyses.take(5)) }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
fun HeaderMarketCard(session: SessionInfo, now: Long) {
    val utcText = SimpleDateFormat("HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(now))
    Column(modifier = Modifier.fillMaxWidth().background(UiColors.Surface, RoundedCornerShape(14.dp)).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("INNER CIRCLE TRADER ", color = UiColors.PrimaryYellow, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
            Box(Modifier.size(4.dp).background(UiColors.PrimaryYellow, CircleShape))
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row {
                Text("XAU", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = UiColors.TextPrimary)
                Text("/", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = UiColors.PrimaryYellow)
                Text("USD", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = UiColors.TextPrimary)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("UTC Time", fontSize = 11.sp, color = UiColors.TextSecondary)
                Text(utcText, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = UiColors.TextPrimary, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).background(if (session.active) UiColors.BullishGreen else UiColors.TextSecondary, CircleShape))
                    Spacer(Modifier.width(4.dp))
                    Text(session.name, fontSize = 11.sp, color = if (session.active) UiColors.BullishGreen else UiColors.TextSecondary)
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text("Smart Money Concept • ICT Methodology", fontSize = 12.sp, color = UiColors.TextSecondary)
    }
}

@Composable
fun SessionsCard(session: SessionInfo, now: Long) {
    val rows = sessionRows(now)
    Column(Modifier.fillMaxWidth().background(UiColors.Surface, RoundedCornerShape(14.dp)).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Schedule, null, tint = UiColors.PrimaryYellow, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
            Text("Trading Sessions Auto DST", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = UiColors.TextPrimary)
        }
        Spacer(Modifier.height(10.dp))
        rows.forEachIndexed { index, it ->
            SessionRowAuto(it.name, it.utcRange, it.wibRange, it.name == session.name && session.active)
            if (index < rows.lastIndex) HorizontalDivider(color = UiColors.SurfaceLight, modifier = Modifier.padding(vertical = 8.dp))
        }
    }
}

@Composable
fun ConceptsCoveredCard(concepts: List<ConceptInfo>, activeCount: Int) {
    Column(Modifier.fillMaxWidth().background(UiColors.Surface, RoundedCornerShape(14.dp)).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.RadioButtonChecked, null, tint = UiColors.PrimaryYellow, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
            Text("ICT Concepts • $activeCount/${concepts.size} Active", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = UiColors.TextPrimary)
        }
        Spacer(Modifier.height(10.dp))
        concepts.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { ConceptCard(Modifier.weight(1f), it) }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

// ─────────────── ANALYZE TAB ───────────────

@Composable
fun AnalyzeTab(viewModel: TradingBotViewModel, session: SessionInfo) {
    var selectedTimeframe by remember { mutableStateOf("M5") }
    val isAnalyzing by viewModel.isAnalyzing.collectAsState()
    val result by viewModel.analysisResultJson.collectAsState()
    val error by viewModel.analysisErrorText.collectAsState()

    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Spacer(Modifier.height(4.dp)) }
        item {
            Text("ANALISIS ICT", color = UiColors.PrimaryYellow, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Text("XAUUSD Smart Money Analysis", color = UiColors.TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("Session otomatis: ${session.name}", color = UiColors.TextSecondary, fontSize = 13.sp)
        }
        item {
            Column(Modifier.fillMaxWidth().background(UiColors.Surface, RoundedCornerShape(14.dp)).padding(18.dp)) {
                Text("TIMEFRAME", color = UiColors.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp))
                listOf("M1", "M5", "M15", "M30", "H1", "H4", "D1", "W1").chunked(5).forEach { row ->
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { tf -> TimeframeChip(tf, selectedTimeframe == tf) { selectedTimeframe = tf } }
                    }
                    Spacer(Modifier.height(6.dp))
                }
                Spacer(Modifier.height(10.dp))
                AutoSessionBox(session)
                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = { viewModel.analyzeIct(selectedTimeframe, session.name, "") },
                    enabled = !isAnalyzing,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = UiColors.PrimaryYellow, contentColor = Color.Black, disabledContainerColor = UiColors.SurfaceLight),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (isAnalyzing) { CircularProgressIndicator(Modifier.size(20.dp), color = UiColors.TextSecondary); Spacer(Modifier.width(8.dp)); Text("Menganalisis...") }
                    else { Icon(Icons.Default.FlashOn, null); Spacer(Modifier.width(8.dp)); Text("Analisis ICT Sekarang", fontWeight = FontWeight.Bold) }
                }
            }
        }
        if (error != null) item { ErrorCard(error.orEmpty()) }
        if (result != null) item { AnalysisResultCard(result.orEmpty()) }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

// ─────────────── HISTORY TRADE TAB ───────────────

@Composable
fun JournalTab(viewModel: TradingBotViewModel) {
    val trades by viewModel.trades.collectAsState()
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Spacer(Modifier.height(4.dp)) }
        item {
            Text("HISTORY TRADE", color = UiColors.PrimaryYellow, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Text("Riwayat TP / SL XAUUSD", color = UiColors.TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("Trade masuk setelah terminal notifikasi TP atau SL.", color = UiColors.TextSecondary, fontSize = 13.sp)
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricCard(Modifier.weight(1f), trades.size.toString(), "Total", UiColors.PrimaryYellow)
                MetricCard(Modifier.weight(1f), trades.count { it.result == "WIN" }.toString(), "TP", UiColors.BullishGreen)
                MetricCard(Modifier.weight(1f), trades.count { it.result == "LOSS" }.toString(), "SL", UiColors.BearishRed)
            }
        }
        if (trades.isEmpty()) {
            item { EmptyCard("Belum ada trade selesai. Setup harus ACTIVE lalu menyentuh TP/SL dulu.") }
        } else {
            items(trades, key = { it.id }) { trade ->
                TradeHistoryCard(trade.type, trade.result, trade.entryPrice, trade.takeProfit, trade.stopLoss, trade.timestamp)
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

// ─────────────── MARKET EVENT FEED (TERMINAL) ───────────────

@Composable
fun TerminalTab(viewModel: TradingBotViewModel) {
    val logs by viewModel.logs.collectAsState()
    LazyColumn(Modifier.fillMaxSize().background(Color(0xFF050709)).padding(horizontal = 14.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                Icon(Icons.Default.Terminal, null, tint = UiColors.PrimaryYellow, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("MARKET EVENT FEED", color = UiColors.PrimaryYellow, fontWeight = FontWeight.Bold, fontSize = 15.sp, letterSpacing = 1.sp)
            }
            HorizontalDivider(color = UiColors.SurfaceLight, modifier = Modifier.padding(bottom = 8.dp))
        }
        items(logs, key = { "$it-${logs.indexOf(it)}" }) { log ->
            val color = eventColor(log)
            val prefix = eventPrefix(log)
            Text("$prefix $log", color = color, fontSize = 12.sp, lineHeight = 18.sp, fontFamily = FontFamily.Monospace, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

fun eventColor(log: String): Color {
    val upper = log.uppercase(Locale.US)
    return when {
        upper.contains("TP1 TERCAPAI") || upper.contains("TP2 TERCAPAI") -> UiColors.BullishGreen
        upper.contains("STOP TERCAPAI") -> UiColors.BearishRed
        upper.contains("SETUP ACTIVE") || upper.contains("SETUP WAIT") -> UiColors.PrimaryYellow
        upper.contains("BOS") || upper.contains("MSS") || upper.contains("CHOCH") -> UiColors.PrimaryYellow
        upper.contains("CISD") -> Color(0xFFFF9800)
        upper.contains("DISPLACEMENT") && upper.contains("BULLISH") -> UiColors.BullishGreen
        upper.contains("DISPLACEMENT") && upper.contains("BEARISH") -> UiColors.BearishRed
        upper.contains("FVG") || upper.contains("OB ") || upper.contains("ORDER BLOCK") -> UiColors.Cyan
        upper.contains("LIQUIDITY") || upper.contains("SWEPT") || upper.contains("EQH") || upper.contains("EQL") -> Color(0xFFE879F9)
        upper.contains("PREMIUM") || upper.contains("DISCOUNT") -> Color(0xFF94A3B8)
        upper.startsWith("EVENT") -> UiColors.PrimaryYellow
        upper.contains("CLOSED") || upper.contains("REST ") -> Color(0xFF475569)
        upper.contains("HISTORICAL") || upper.contains("CONNECTING") || upper.contains("FETCHING") -> Color(0xFF64748B)
        else -> Color(0xFF6EE7B7)
    }
}

fun eventPrefix(log: String): String {
    val upper = log.uppercase(Locale.US)
    return when {
        upper.contains("TP1") || upper.contains("TP2") -> "💰"
        upper.contains("STOP TERCAPAI") -> "🛑"
        upper.contains("SETUP") -> "⚡"
        upper.contains("BOS") || upper.contains("MSS") || upper.contains("CHOCH") -> "📐"
        upper.contains("CISD") -> "🔄"
        upper.contains("DISPLACEMENT") -> "💥"
        upper.contains("FVG") -> "⚖️"
        upper.contains("OB ") || upper.contains("ORDER BLOCK") -> "🧱"
        upper.contains("LIQUIDITY") || upper.contains("SWEPT") -> "💧"
        upper.contains("EQH") || upper.contains("EQL") -> "🔲"
        upper.contains("PREMIUM") || upper.contains("DISCOUNT") -> "📏"
        upper.startsWith("EVENT") -> "⚡"
        else -> "›"
    }
}

// ─────────────── SETTINGS TAB ───────────────

@Composable
fun SettingsTab(viewModel: TradingBotViewModel) {
    var twelve by remember { mutableStateOf(viewModel.settings.twelveApiKey) }
    var deepseek by remember { mutableStateOf(viewModel.settings.deepseekApiKey.takeIf { it != "LOCAL_RULE_ENGINE" } ?: "") }
    LazyColumn(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("API Keys Configuration", color = UiColors.PrimaryYellow, fontSize = 22.sp, fontWeight = FontWeight.Bold) }
        item { ApiField("Twelve Data API Key", twelve) { twelve = it } }
        item { ApiField("DeepSeek API Key (opsional)", deepseek) { deepseek = it } }
        item {
            Button(onClick = { viewModel.saveKeys(twelve, deepseek); viewModel.startBot() }, modifier = Modifier.fillMaxWidth().height(52.dp), colors = ButtonDefaults.buttonColors(containerColor = UiColors.PrimaryYellow, contentColor = Color.Black), shape = RoundedCornerShape(12.dp)) {
                Icon(Icons.Default.Save, null); Spacer(Modifier.width(8.dp)); Text(if (viewModel.settings.areKeysSet()) "API Keys Tersimpan" else "Simpan API Keys", fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ─────────────── SHARED COMPONENTS ───────────────

@Composable
fun ApiField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label) }, modifier = Modifier.fillMaxWidth(), colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = UiColors.SurfaceLight, focusedBorderColor = UiColors.PrimaryYellow, unfocusedTextColor = UiColors.TextPrimary, focusedTextColor = UiColors.TextPrimary, unfocusedContainerColor = Color.Transparent, focusedContainerColor = Color.Transparent), shape = RoundedCornerShape(8.dp))
}

@Composable
fun TimeframeChip(tf: String, selected: Boolean, onClick: () -> Unit) {
    Box(Modifier.clip(RoundedCornerShape(8.dp)).background(if (selected) UiColors.PrimaryYellow else UiColors.SurfaceLight).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
        Text(tf, color = if (selected) Color.Black else UiColors.TextSecondary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

@Composable
fun AutoSessionBox(session: SessionInfo) {
    Column(Modifier.fillMaxWidth().border(1.dp, UiColors.SurfaceLight, RoundedCornerShape(8.dp)).padding(12.dp)) {
        Text("AUTO SESSION", color = UiColors.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(session.name, color = if (session.active) UiColors.BullishGreen else UiColors.TextSecondary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text(session.utcRange, color = UiColors.TextSecondary, fontSize = 12.sp)
        }
        Text("WIB: ${session.wibRange}", color = UiColors.TextSecondary, fontSize = 11.sp)
    }
}

@Composable
fun AnalysisResultCard(raw: String) {
    val json = parseJson(raw) ?: return
    val bias = json.optString("bias", "-")
    val confidence = json.optInt("confidence_score", 0)
    val price = json.optDouble("current_price", 0.0)
    val ms = json.optJSONObject("market_structure")
    val ts = json.optJSONObject("trade_setup")
    val color = when (bias.uppercase(Locale.US)) { "BULLISH" -> UiColors.BullishGreen; "BEARISH" -> UiColors.BearishRed; else -> UiColors.PrimaryYellow }
    Column(Modifier.fillMaxWidth().background(UiColors.Surface, RoundedCornerShape(14.dp)).border(1.dp, color.copy(alpha = 0.3f), RoundedCornerShape(14.dp)).padding(16.dp)) {
        Text("HASIL ANALISIS ICT", color = UiColors.PrimaryYellow, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(bias, color = color, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("$confidence%", color = UiColors.TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(10.dp)); HorizontalDivider(color = UiColors.SurfaceLight); Spacer(Modifier.height(8.dp))
        AnalysisRow("Current Price", if (price > 0) "$${String.format(Locale.US, "%.2f", price)}" else "-")
        AnalysisRow("Summary", json.optString("daily_bias_summary", "-"))
        SectionTitle("MARKET STRUCTURE")
        AnalysisRow("Trend", ms?.optString("trend", "-") ?: "-")
        AnalysisRow("BOS", ms?.optString("last_bos", "-") ?: "-")
        AnalysisRow("CHoCH/MSS", ms?.optString("choch", "-") ?: "-")
        AnalysisRow("Liquidity", ms?.optString("liquidity", "-") ?: "-")
        AnalysisRow("FVG", ms?.optString("fvg", "-") ?: "-")
        AnalysisRow("OB", ms?.optString("order_block", "-") ?: "-")
        SectionTitle("TRADE SETUP")
        AnalysisRow("Status", ts?.optString("status", "-")?.uppercase(Locale.US) ?: "-")
        AnalysisRow("Entry", ts?.optString("entry_zone", "-") ?: "-")
        AnalysisRow("TP1", formatTradeValue(ts?.opt("tp1")))
        AnalysisRow("TP2", formatTradeValue(ts?.opt("tp2")))
        AnalysisRow("SL", formatTradeValue(ts?.opt("stop_loss")))
        SectionTitle("ICT DETAIL")
        json.optJSONObject("order_blocks")?.let { AnalysisRow("Bullish OB", it.optString("bullish_ob", "-")); AnalysisRow("Bearish OB", it.optString("bearish_ob", "-")); AnalysisRow("OB Notes", it.optString("description", "-")) }
        json.optJSONObject("fvg")?.let { AnalysisRow("Bullish FVG", it.optString("bullish_fvg", "-")); AnalysisRow("Bearish FVG", it.optString("bearish_fvg", "-")); AnalysisRow("FVG Notes", it.optString("description", "-")) }
        json.optJSONObject("premium_discount")?.let { AnalysisRow("Zone", it.optString("current_zone", "-")); AnalysisRow("OTE", it.optString("ote_zone", "-")) }
    }
}

@Composable
fun RecentAnalysesCard(items: List<com.example.data.database.IctAnalysisEntity>) {
    Column(Modifier.fillMaxWidth().background(UiColors.Surface, RoundedCornerShape(14.dp)).padding(14.dp)) {
        Text("Recent Analyses", color = UiColors.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        if (items.isEmpty()) Text("Belum ada analisis", color = UiColors.TextSecondary, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, fontSize = 13.sp)
        items.forEach { AnalysisRow("${it.bias} • ${it.timeframe}", "${it.confidence}% • ${it.price}") }
    }
}

@Composable
fun ConceptCard(modifier: Modifier, concept: ConceptInfo) {
    val statusColor = when (concept.status) {
        "ACTIVE" -> UiColors.BullishGreen
        "CONTEXT" -> UiColors.PrimaryYellow
        "WAIT" -> UiColors.WaitGray
        else -> UiColors.TextSecondary
    }
    Column(modifier.background(UiColors.SurfaceLight, RoundedCornerShape(12.dp)).padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(concept.icon, fontSize = 18.sp)
            Text(concept.status, color = statusColor, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(4.dp))
        Text(concept.title, color = UiColors.PrimaryYellow, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(concept.timeframe, color = statusColor, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Text(concept.value, color = UiColors.TextSecondary, fontSize = 11.sp, lineHeight = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun MetricCard(modifier: Modifier, value: String, label: String, color: Color) {
    Column(modifier.background(UiColors.Surface, RoundedCornerShape(10.dp)).border(1.dp, UiColors.SurfaceLight, RoundedCornerShape(10.dp)).padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = color, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(label, color = UiColors.TextSecondary, fontSize = 11.sp)
    }
}

@Composable
fun ActionCard(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(UiColors.Surface, RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(42.dp).background(UiColors.PrimaryYellow, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = Color.Black) }
        Spacer(Modifier.width(12.dp))
        Column { Text(title, color = UiColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp); Text(subtitle, color = UiColors.TextSecondary, fontSize = 11.sp) }
    }
}

@Composable
fun SessionRowAuto(name: String, utc: String, wib: String, active: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column {
            Text(name, color = if (active) UiColors.BullishGreen else UiColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text("WIB: $wib", color = UiColors.TextSecondary, fontSize = 11.sp)
        }
        Text(utc, color = UiColors.TextSecondary, fontSize = 12.sp)
    }
}

@Composable
fun SectionTitle(text: String) { Spacer(Modifier.height(10.dp)); Text(text, color = UiColors.PrimaryYellow, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp); Spacer(Modifier.height(4.dp)) }

@Composable
fun AnalysisRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = UiColors.TextSecondary, fontSize = 12.sp, modifier = Modifier.weight(0.9f))
        Text(value, color = UiColors.TextPrimary, fontSize = 12.sp, textAlign = TextAlign.End, modifier = Modifier.weight(1.3f), maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun ErrorCard(text: String) {
    Column(Modifier.fillMaxWidth().background(Color(0xFF2A1C1C), RoundedCornerShape(14.dp)).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.ErrorOutline, null, tint = UiColors.BearishRed); Spacer(Modifier.width(8.dp)); Text("ANALISIS GAGAL", color = UiColors.BearishRed, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.height(4.dp))
        Text(text, color = Color(0xFFFF8A80), fontSize = 12.sp)
    }
}

@Composable
fun EmptyCard(text: String) { Text(text, color = UiColors.TextSecondary, textAlign = TextAlign.Center, fontSize = 13.sp, modifier = Modifier.fillMaxWidth().background(UiColors.Surface, RoundedCornerShape(14.dp)).padding(20.dp)) }

@Composable
fun TradeHistoryCard(type: String, result: String, entry: Double, tp: Double, sl: Double, timestamp: Long) {
    val color = if (result == "WIN") UiColors.BullishGreen else UiColors.BearishRed
    Column(Modifier.fillMaxWidth().background(UiColors.Surface, RoundedCornerShape(12.dp)).border(1.dp, UiColors.SurfaceLight, RoundedCornerShape(12.dp)).padding(14.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("$type • $result", color = color, fontWeight = FontWeight.Bold, fontSize = 13.sp); Text(SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()).format(Date(timestamp)), color = UiColors.TextSecondary, fontSize = 11.sp) }
        AnalysisRow("Entry", String.format(Locale.US, "%.2f", entry))
        AnalysisRow("TP", String.format(Locale.US, "%.2f", tp))
        AnalysisRow("SL", String.format(Locale.US, "%.2f", sl))
    }
}

// ─────────────── HELPER FUNCTIONS ───────────────

fun parseJson(raw: String): JSONObject? = try { JSONObject(raw) } catch (_: Exception) { null }

fun buildConcepts(json: JSONObject?, timeframe: String, session: SessionInfo): List<ConceptInfo> {
    val ms = json?.optJSONObject("market_structure")
    val ob = json?.optJSONObject("order_blocks")
    val fvg = json?.optJSONObject("fvg")
    val liq = json?.optJSONObject("liquidity")
    val pd = json?.optJSONObject("premium_discount")
    val setup = json?.optJSONObject("trade_setup")
    return listOf(
        ConceptInfo("Market Structure", "📐", conceptStatus(ms?.optString("last_bos") != null && ms.optString("last_bos") != "None" && ms.optString("last_bos") != "-"), timeframe, ms?.optString("last_bos", "No BOS") ?: "No data"),
        ConceptInfo("Order Blocks", "🧱", poiStatus(ob?.optString("bullish_ob", "-"), ob?.optString("bearish_ob", "-")), timeframe, ob?.optString("description", "No OB") ?: "No data"),
        ConceptInfo("Fair Value Gaps", "⚖️", poiStatus(fvg?.optString("bullish_fvg", "-"), fvg?.optString("bearish_fvg", "-")), timeframe, fvg?.optString("description", "No FVG") ?: "No data"),
        ConceptInfo("Liquidity", "💧", conceptStatus(liq?.optBoolean("sweep_occurred", false) == true), timeframe, liq?.optString("description", "No sweep") ?: "No data"),
        ConceptInfo("Premium / Discount", "📏", if (pd != null) "ACTIVE" else "NONE", timeframe, pd?.optString("current_zone", "-")?.plus(" • EQ ${formatTradeValue(pd.opt("equilibrium"))}") ?: "No data"),
        ConceptInfo("Kill Zones", "🎯", if (session.active) "ACTIVE" else "WAIT", "AUTO", "${session.name} • ${session.utcRange}"),
        ConceptInfo("Trade Setup", "⚡", setupStatus(setup?.optString("status", "wait")), timeframe, setup?.optString("entry_zone", "No setup") ?: "No data")
    )
}

fun conceptStatus(active: Boolean): String = if (active) "ACTIVE" else "NONE"

fun poiStatus(bullish: String?, bearish: String?): String {
    val hasBullish = bullish != null && bullish != "-" && !bullish.isBlank()
    val hasBearish = bearish != null && bearish != "-" && !bearish.isBlank()
    if (!hasBullish && !hasBearish) return "NONE"
    val hasActive = (hasBullish && bullish!!.contains("ACTIVE", true)) || (hasBearish && bearish!!.contains("ACTIVE", true))
    val hasContext = (hasBullish && bullish!!.contains("CONTEXT", true)) || (hasBearish && bearish!!.contains("CONTEXT", true))
    return when {
        hasActive -> "ACTIVE"
        hasContext -> "CONTEXT"
        hasBullish || hasBearish -> "ACTIVE"
        else -> "NONE"
    }
}

fun setupStatus(status: String?): String = when (status?.lowercase(Locale.US)) {
    "valid" -> "ACTIVE"
    "wait" -> "WAIT"
    else -> "NONE"
}

fun formatTradeValue(value: Any?): String = when (value) { is Number -> if (value.toDouble() > 0.0) String.format(Locale.US, "%.2f", value.toDouble()) else "-"; is String -> value; else -> "-" }

fun currentSessionInfo(now: Long): SessionInfo {
    val all = sessionRows(now)
    val active = all.firstOrNull { it.active }
    return active ?: SessionInfo("Off-Session", "Off-Session", "-", "-", false)
}

fun sessionRows(now: Long): List<SessionInfo> = listOf(
    buildSession("Asian Kill Zone", "Asia/Tokyo", 9, 0, 12, 0, now),
    buildSession("London Judas Swing", "Europe/London", 7, 0, 8, 30, now),
    buildSession("London Open Kill Zone", "Europe/London", 8, 0, 12, 0, now),
    buildSession("New York Judas Swing", "America/New_York", 8, 0, 9, 30, now),
    buildSession("New York Open Kill Zone", "America/New_York", 8, 30, 11, 30, now),
    buildSession("Silver Bullet", "America/New_York", 10, 0, 11, 0, now),
    buildSession("Swing Session", "America/New_York", 13, 30, 16, 0, now)
)

fun buildSession(name: String, zoneId: String, sh: Int, sm: Int, eh: Int, em: Int, now: Long): SessionInfo {
    val zone = TimeZone.getTimeZone(zoneId)
    val start = Calendar.getInstance(zone).apply { timeInMillis = now; set(Calendar.HOUR_OF_DAY, sh); set(Calendar.MINUTE, sm); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
    val end = Calendar.getInstance(zone).apply { timeInMillis = now; set(Calendar.HOUR_OF_DAY, eh); set(Calendar.MINUTE, em); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
    if (end.timeInMillis <= start.timeInMillis) end.add(Calendar.DAY_OF_MONTH, 1)
    val active = now in start.timeInMillis until end.timeInMillis
    return SessionInfo(name, if (active) name else "Off-Session", formatRange(start.timeInMillis, end.timeInMillis, "UTC"), formatRange(start.timeInMillis, end.timeInMillis, "Asia/Jakarta"), active)
}

fun formatRange(start: Long, end: Long, zone: String): String {
    val sdf = SimpleDateFormat("HH:mm", Locale.US).apply { timeZone = TimeZone.getTimeZone(zone) }
    return "${sdf.format(Date(start))} - ${sdf.format(Date(end))}"
}
