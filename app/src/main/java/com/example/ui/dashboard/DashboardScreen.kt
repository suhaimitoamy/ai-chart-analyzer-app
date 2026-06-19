package com.example.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

object UiColors {
    val Background = Color(0xFF0F111A)
    val Surface = Color(0xFF1B1D26)
    val SurfaceLight = Color(0xFF262833)
    val TextPrimary = Color(0xFFFFFFFF)
    val TextSecondary = Color(0xFFA1A3AB)
    val PrimaryYellow = Color(0xFFFFC107)
    val BearishRed = Color(0xFFFF5252)
    val BullishGreen = Color(0xFF4ADE80)
}

@Composable
fun DashboardScreen(viewModel: TradingBotViewModel = viewModel()) {
    var currentTab by remember { mutableStateOf("Dashboard") }

    LaunchedEffect(Unit) {
        viewModel.startBot()
    }

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = UiColors.Surface,
                contentColor = UiColors.TextSecondary
            ) {
                NavigationBarItem(
                    selected = currentTab == "Dashboard",
                    onClick = { currentTab = "Dashboard" },
                    icon = { Icon(Icons.Default.GridView, contentDescription = null) },
                    label = { Text("Dashboard") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = UiColors.PrimaryYellow,
                        unselectedIconColor = UiColors.TextSecondary,
                        selectedTextColor = UiColors.PrimaryYellow,
                        unselectedTextColor = UiColors.TextSecondary,
                        indicatorColor = Color.Transparent
                    )
                )
                NavigationBarItem(
                    selected = currentTab == "Analyze",
                    onClick = { currentTab = "Analyze" },
                    icon = { Icon(Icons.Default.GpsFixed, contentDescription = null) },
                    label = { Text("ICT Analyze") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = UiColors.PrimaryYellow,
                        unselectedIconColor = UiColors.TextSecondary,
                        selectedTextColor = UiColors.PrimaryYellow,
                        unselectedTextColor = UiColors.TextSecondary,
                        indicatorColor = Color.Transparent
                    )
                )
                NavigationBarItem(
                    selected = currentTab == "Journal",
                    onClick = { currentTab = "Journal" },
                    icon = { Icon(Icons.Default.MenuBook, contentDescription = null) },
                    label = { Text("Trade Journal") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = UiColors.PrimaryYellow,
                        unselectedIconColor = UiColors.TextSecondary,
                        selectedTextColor = UiColors.PrimaryYellow,
                        unselectedTextColor = UiColors.TextSecondary,
                        indicatorColor = Color.Transparent
                    )
                )
                NavigationBarItem(
                    selected = currentTab == "Terminal",
                    onClick = { currentTab = "Terminal" },
                    icon = { Icon(Icons.Default.Terminal, contentDescription = null) },
                    label = { Text("Terminal") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = UiColors.PrimaryYellow,
                        unselectedIconColor = UiColors.TextSecondary,
                        selectedTextColor = UiColors.PrimaryYellow,
                        unselectedTextColor = UiColors.TextSecondary,
                        indicatorColor = Color.Transparent
                    )
                )
                NavigationBarItem(
                    selected = currentTab == "Settings",
                    onClick = { currentTab = "Settings" },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text("Settings") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = UiColors.PrimaryYellow,
                        unselectedIconColor = UiColors.TextSecondary,
                        selectedTextColor = UiColors.PrimaryYellow,
                        unselectedTextColor = UiColors.TextSecondary,
                        indicatorColor = Color.Transparent
                    )
                )
            }
        },
        containerColor = UiColors.Background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            TopBar()
            HorizontalDivider(color = UiColors.SurfaceLight)
            when (currentTab) {
                "Dashboard" -> DashboardTab(viewModel = viewModel, onNavigateAnalyze = { currentTab = "Analyze" })
                "Analyze" -> AnalyzeTab(viewModel)
                "Journal" -> JournalTab(viewModel)
                "Terminal" -> TerminalTab(viewModel)
                "Settings" -> SettingsTab(viewModel)
            }
        }
    }
}

@Composable
fun TopBar() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(UiColors.PrimaryYellow)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("XAU", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text("XAUUSD ICT", color = UiColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text("Inner Circle Trader", color = UiColors.PrimaryYellow, fontSize = 12.sp)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(UiColors.BullishGreen, CircleShape)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text("Live", color = UiColors.TextSecondary, fontSize = 12.sp)
        }
    }
}

@Composable
fun DashboardTab(viewModel: TradingBotViewModel, onNavigateAnalyze: () -> Unit) {
    val ictAnalyses by viewModel.ictAnalyses.collectAsState()
    val recentAnalysis = ictAnalyses.firstOrNull()
    val total = ictAnalyses.size
    val bullish = ictAnalyses.count { it.bias.equals("BULLISH", true) }
    val bearish = ictAnalyses.count { it.bias.equals("BEARISH", true) }
    
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item { Spacer(modifier = Modifier.height(8.dp)) }
        // Top Section: INNER CIRCLE TRADER
        item {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("INNER CIRCLE TRADER ", color = UiColors.PrimaryYellow, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
                    Box(modifier = Modifier.size(4.dp).background(UiColors.PrimaryYellow, CircleShape))
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row {
                        Text("XAU", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = UiColors.TextPrimary)
                        Text("/", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = UiColors.PrimaryYellow)
                        Text("USD", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = UiColors.TextPrimary)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("UTC Time", fontSize = 12.sp, color = UiColors.TextSecondary)
                        Text("07:33", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = UiColors.TextPrimary)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(modifier = Modifier.size(6.dp).background(UiColors.TextSecondary, CircleShape))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Off-Session", fontSize = 12.sp, color = UiColors.TextSecondary)
                        }
                    }
                }
                Text("Smart Money Concept • ICT Methodology", fontSize = 14.sp, color = UiColors.TextSecondary)
            }
        }

        // Card: Latest AI Bias
        if (recentAnalysis != null) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (recentAnalysis.bias.equals("BULLISH", true)) Color(0xFF162B1D) else Color(0xFF2A1C1C), RoundedCornerShape(16.dp))
                        .border(1.dp, if (recentAnalysis.bias.equals("BULLISH", true)) Color(0xFF234B2F) else Color(0xFF4A2B2B), RoundedCornerShape(16.dp))
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (recentAnalysis.bias.equals("BULLISH", true)) Icons.Default.TrendingUp else Icons.Default.TrendingDown, 
                                contentDescription = null, 
                                tint = if (recentAnalysis.bias.equals("BULLISH", true)) UiColors.BullishGreen else UiColors.BearishRed, 
                                modifier = Modifier.size(32.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text("LATEST AI BIAS", color = UiColors.TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                Text(recentAnalysis.bias, color = if (recentAnalysis.bias.equals("BULLISH", true)) UiColors.BullishGreen else UiColors.BearishRed, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("Confidence", color = UiColors.TextSecondary, fontSize = 12.sp)
                            Text("${recentAnalysis.confidence}%", color = UiColors.TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // Stats row
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard(modifier = Modifier.weight(1f), total.toString(), "Total Analyses", color = UiColors.TextPrimary)
                StatCard(modifier = Modifier.weight(1f), bullish.toString(), "Bullish Signals", color = UiColors.BullishGreen, icon = Icons.Default.TrendingUp)
                StatCard(modifier = Modifier.weight(1f), bearish.toString(), "Bearish Signals", color = UiColors.BearishRed, icon = Icons.Default.TrendingDown)
            }
        }

        // Run ICT Analysis card
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(UiColors.Surface, RoundedCornerShape(16.dp))
                    .clickable { onNavigateAnalyze() }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(UiColors.PrimaryYellow, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.FlashOn, contentDescription = null, tint = Color.Black)
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Run ICT Analysis", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = UiColors.TextPrimary)
                    Text("AI akan analisis XAUUSD menggunakan konsep ICT", fontSize = 12.sp, color = UiColors.TextSecondary)
                }
                Icon(Icons.Default.ChevronRight, contentDescription = null, tint = UiColors.TextSecondary)
            }
        }

        // Trade Sessions
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(UiColors.Surface, RoundedCornerShape(16.dp))
                    .padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Schedule, contentDescription=null, tint = UiColors.PrimaryYellow, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Trading Sessions (UTC)", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = UiColors.TextPrimary)
                }
                Spacer(modifier = Modifier.height(16.dp))
                SessionRow("Asian", "WIB: 07:00-10:00", "00:00 - 03:00")
                HorizontalDivider(color = UiColors.SurfaceLight, modifier = Modifier.padding(vertical = 12.dp))
                SessionRow("London", "WIB: 15:00-19:00", "08:00 - 12:00")
                HorizontalDivider(color = UiColors.SurfaceLight, modifier = Modifier.padding(vertical = 12.dp))
                SessionRow("New York", "WIB: 20:00-00:00", "13:00 - 17:00")
            }
        }

        item { Spacer(modifier = Modifier.height(8.dp)) }

        // ICT Concepts Covered
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(UiColors.Surface, RoundedCornerShape(16.dp))
                    .padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.RadioButtonChecked, contentDescription=null, tint = UiColors.PrimaryYellow, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("ICT Concepts Covered", fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = UiColors.TextPrimary)
                }
                Spacer(modifier = Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ConceptCard(modifier=Modifier.weight(1f), icon = "\uD83D\uDCD0", title = "Market Structure", sub = "BOS • CHoCH • MSB")
                    ConceptCard(modifier=Modifier.weight(1f), icon = "\uD83E\uDDF1", title = "Order Blocks", sub = "Bullish OB • Bearish OB")
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ConceptCard(modifier=Modifier.weight(1f), icon = "⚖️", title = "Fair Value Gaps", sub = "FVG • IFVG • Breaker")
                    ConceptCard(modifier=Modifier.weight(1f), icon = "💧", title = "Liquidity", sub = "BSL • SSL • EQH • EQL")
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ConceptCard(modifier=Modifier.weight(1f), icon = "\uD83D\uDCCF", title = "Premium / Discount", sub = "50% Equilibrium Zone")
                    ConceptCard(modifier=Modifier.weight(1f), icon = "🎯", title = "Kill Zones", sub = "London • NY • Silver Bullet")
                }
            }
        }

        // Recent Analyses
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(UiColors.Surface, RoundedCornerShape(16.dp))
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(), 
                    horizontalArrangement = Arrangement.SpaceBetween, 
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Recent Analyses", fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = UiColors.TextPrimary)
                    Text("View all →", fontSize = 12.sp, color = UiColors.PrimaryYellow)
                }
                Spacer(modifier = Modifier.height(16.dp))

                if (ictAnalyses.isEmpty()) {
                    Text("Belum ada analisis", color = UiColors.TextSecondary, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(16.dp))
                } else {
                    ictAnalyses.take(5).forEachIndexed { index, it ->
                        RecentAnalysisRow(
                            isBullish = it.bias.equals("BULLISH", true),
                            tf = it.timeframe,
                            session = it.session,
                            confidence = "${it.confidence}%",
                            date = it.date
                        )
                        if (index < ictAnalyses.take(5).size - 1) {
                            HorizontalDivider(color = UiColors.SurfaceLight)
                        }
                    }
                }
            }
        }
        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalyzeTab(viewModel: TradingBotViewModel) {
    var selectedTimeframe by remember { mutableStateOf("H1") }
    var selectedKillZone by remember { mutableStateOf("London Open Kill Zone") }
    var expandedKillZone by remember { mutableStateOf(false) }
    var notes by remember { mutableStateOf("") }
    val killZones = listOf("Asian Kill Zone", "London Open Kill Zone", "New York Kill Zone", "Silver Bullet")

    val isAnalyzing by viewModel.isAnalyzing.collectAsState()
    val ictAnalyses by viewModel.ictAnalyses.collectAsState()
    val recentAnalysis = ictAnalyses.firstOrNull()

    var selectedFileName by remember { mutableStateOf<String?>(null) }
    val filePickerLauncher = rememberLauncherForActivityResult(contract = ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            viewModel.uploadDataFile(uri)
            selectedFileName = uri.lastPathSegment ?: "Data_Backtest_SMC.csv"
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item { Spacer(modifier = Modifier.height(8.dp)) }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.GpsFixed, contentDescription = null, tint = UiColors.PrimaryYellow, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("ANALISIS ICT", color = UiColors.PrimaryYellow, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text("XAUUSD Smart Money Analysis", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = UiColors.TextPrimary)
            Spacer(modifier = Modifier.height(4.dp))
            Text("AI menganalisis XAUUSD secara mendalam dengan konsep ICT murni", fontSize = 14.sp, color = UiColors.TextSecondary)
        }

        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(UiColors.Surface, RoundedCornerShape(16.dp))
                    .padding(20.dp)
            ) {
                Column {
                    Text("TIMEFRAME (PERIODE GRAFIK)", fontSize = 12.sp, color = UiColors.TextSecondary, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(12.dp))
                    val timeframes1 = listOf("M1", "M5", "M15", "M30", "H1")
                    val timeframes2 = listOf("H4", "D1", "W1")
                    Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        timeframes1.forEach { tf ->
                            val isSelected = tf == selectedTimeframe
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSelected) UiColors.PrimaryYellow else UiColors.SurfaceLight)
                                    .clickable { selectedTimeframe = tf }
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(tf, color = if (isSelected) Color.Black else UiColors.TextSecondary, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        timeframes2.forEach { tf ->
                            val isSelected = tf == selectedTimeframe
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSelected) UiColors.PrimaryYellow else UiColors.SurfaceLight)
                                    .clickable { selectedTimeframe = tf }
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(tf, color = if (isSelected) Color.Black else UiColors.TextSecondary, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))
                    Text("KILL ZONE / SESI TRADING", fontSize = 12.sp, color = UiColors.TextSecondary, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Box(modifier = Modifier.fillMaxWidth()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, UiColors.SurfaceLight, RoundedCornerShape(8.dp))
                                .clickable { expandedKillZone = true }
                                .padding(16.dp)
                        ) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(selectedKillZone, color = UiColors.TextPrimary)
                                Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, tint = UiColors.TextSecondary)
                            }
                        }
                        DropdownMenu(
                            expanded = expandedKillZone,
                            onDismissRequest = { expandedKillZone = false },
                            modifier = Modifier
                                .fillMaxWidth(0.9f)
                                .background(UiColors.SurfaceLight)
                        ) {
                            killZones.forEach { selectionOption ->
                                DropdownMenuItem(
                                    text = { Text(selectionOption, color = UiColors.TextPrimary) },
                                    onClick = {
                                        selectedKillZone = selectionOption
                                        expandedKillZone = false
                                    }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))
                    Text("UPLOAD DATA BACKTEST (CSV/TXT) UNTUK TRAINING AI", fontSize = 12.sp, color = UiColors.TextSecondary, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, if (selectedFileName != null) UiColors.BullishGreen else UiColors.SurfaceLight, RoundedCornerShape(8.dp)) 
                            .clickable { filePickerLauncher.launch("*/*") }
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row {
                            Icon(if (selectedFileName != null) Icons.Default.CheckCircle else Icons.Default.UploadFile, contentDescription = null, tint = if (selectedFileName != null) UiColors.BullishGreen else UiColors.TextSecondary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(selectedFileName ?: "Upload hasil backtesting Anda (opsional)", color = if (selectedFileName != null) UiColors.BullishGreen else UiColors.TextSecondary)
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))
                    Text("CATATAN TAMBAHAN (OPSIONAL)", fontSize = 12.sp, color = UiColors.TextSecondary, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = notes,
                        onValueChange = { notes = it },
                        placeholder = { Text("Contoh: harga sudah sweep high kemarin, ada berita NFP hari ini, DSS terbentuk di H1...", color = UiColors.TextSecondary) },
                        modifier = Modifier.fillMaxWidth().height(120.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedBorderColor = UiColors.SurfaceLight,
                            focusedBorderColor = UiColors.PrimaryYellow,
                            unfocusedTextColor = UiColors.TextPrimary,
                            focusedTextColor = UiColors.TextPrimary,
                            unfocusedContainerColor = Color.Transparent,
                            focusedContainerColor = Color.Transparent
                        ),
                        shape = RoundedCornerShape(8.dp)
                    )

                    Spacer(modifier = Modifier.height(24.dp))
                    Button(
                        onClick = { viewModel.analyzeIct(selectedTimeframe, selectedKillZone, notes) },
                        enabled = !isAnalyzing,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = UiColors.PrimaryYellow, contentColor = Color.Black, disabledContainerColor = UiColors.SurfaceLight),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        if (isAnalyzing) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), color = UiColors.TextSecondary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Sedang Menganalisis...", color = UiColors.TextSecondary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        } else {
                            Icon(Icons.Default.FlashOn, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Analisis ICT Sekarang", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        }
                    }
                }
            }
        }
        
        if (recentAnalysis != null) {
            item {
                Column(modifier = Modifier.fillMaxWidth().background(UiColors.Surface, RoundedCornerShape(16.dp)).padding(20.dp)) {
                    Text("HASIL ANALISIS", color = UiColors.PrimaryYellow, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Bias: ${recentAnalysis.bias}", color = UiColors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text("Confidence: ${recentAnalysis.confidence}%", color = UiColors.TextSecondary, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(recentAnalysis.rawResult, color = UiColors.TextPrimary, fontSize = 12.sp)
                }
            }
        }
        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
}

@Composable
fun JournalTab(viewModel: TradingBotViewModel) {
    val ictAnalyses by viewModel.ictAnalyses.collectAsState()
    
    val total = ictAnalyses.size
    val bullish = ictAnalyses.count { it.bias.equals("BULLISH", true) }
    val bearish = ictAnalyses.count { it.bias.equals("BEARISH", true) }
    
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item { Spacer(modifier = Modifier.height(8.dp)) }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.MenuBook, contentDescription = null, tint = UiColors.PrimaryYellow, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("JURNAL TRADING", color = UiColors.PrimaryYellow, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text("Riwayat Analisis XAUUSD", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = UiColors.TextPrimary)
            Spacer(modifier = Modifier.height(4.dp))
            Text("Seluruh riwayat analisis ICT XAUUSD kamu", fontSize = 14.sp, color = UiColors.TextSecondary)
        }

        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard(modifier = Modifier.weight(1f), total.toString(), "Total", color = UiColors.PrimaryYellow, alignCenter = true)
                StatCard(modifier = Modifier.weight(1f), bullish.toString(), "Bullish", color = UiColors.BullishGreen, alignCenter = true)
                StatCard(modifier = Modifier.weight(1f), bearish.toString(), "Bearish", color = UiColors.BearishRed, alignCenter = true)
            }
        }

        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                Icon(Icons.Default.FilterAlt, contentDescription = null, tint = UiColors.TextSecondary)
                Spacer(modifier = Modifier.width(12.dp))
                FilterChip(text = "ALL", isSelected = true)
                Spacer(modifier = Modifier.width(8.dp))
                FilterChip(text = "BULLISH", isSelected = false)
                Spacer(modifier = Modifier.width(8.dp))
                FilterChip(text = "BEARISH", isSelected = false)
                Spacer(modifier = Modifier.width(8.dp))
                FilterChip(text = "NEUTRAL", isSelected = false)
                Spacer(modifier = Modifier.weight(1f))
                Icon(Icons.Default.Sync, contentDescription = null, tint = UiColors.TextSecondary)
            }
        }

        // Journal Items
        if (ictAnalyses.isEmpty()) {
            item {
                Text("Belum ada analisis", color = UiColors.TextSecondary, modifier = Modifier.fillMaxWidth().padding(32.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        } else {
            items(ictAnalyses.size) { index ->
                val it = ictAnalyses[index]
                JournalCard(
                    isBullish = it.bias.equals("BULLISH", true),
                    tf = it.timeframe,
                    session = it.session,
                    confidence = "${it.confidence}%",
                    price = it.price,
                    date = it.date,
                    onDelete = { viewModel.deleteIctAnalysis(it.id) }
                )
            }
        }
        
        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
}


@Composable
fun StatCard(modifier: Modifier = Modifier, value: String, subtitle: String, color: Color, icon: ImageVector? = null, alignCenter: Boolean = false) {
    Column(
        modifier = modifier
            .background(UiColors.Surface, RoundedCornerShape(12.dp))
            .border(1.dp, UiColors.SurfaceLight, RoundedCornerShape(12.dp))
            .padding(16.dp),
        horizontalAlignment = if (alignCenter) Alignment.CenterHorizontally else Alignment.Start
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.height(8.dp))
        }
        Text(value, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = color)
        Text(subtitle, fontSize = 12.sp, color = UiColors.TextSecondary)
    }
}

@Composable
fun SessionRow(name: String, wibText: String, utcText: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column {
            Text(name, color = UiColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(modifier = Modifier.height(4.dp))
            Text(wibText, color = UiColors.TextSecondary, fontSize = 12.sp)
        }
        Text(utcText, color = UiColors.TextSecondary, fontSize = 12.sp)
    }
}

@Composable
fun ConceptCard(modifier: Modifier = Modifier, icon: String, title: String, sub: String) {
    Column(modifier = modifier
        .background(UiColors.SurfaceLight, RoundedCornerShape(12.dp))
        .padding(12.dp)
    ) {
        Text(icon, fontSize = 20.sp)
        Spacer(modifier = Modifier.height(8.dp))
        Text(title, color = UiColors.PrimaryYellow, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(4.dp))
        Text(sub, color = UiColors.TextSecondary, fontSize = 10.sp)
    }
}

@Composable
fun RecentAnalysisRow(isBullish: Boolean, tf: String, session: String, confidence: String, date: String) {
    val color = if (isBullish) UiColors.BullishGreen else UiColors.BearishRed
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(8.dp).background(color, CircleShape))
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Row {
                    Text(tf, fontWeight = FontWeight.Bold, color = UiColors.TextPrimary, fontSize = 14.sp)
                    Text(" — ", color = UiColors.TextSecondary, fontSize = 14.sp)
                    Text(if (isBullish) "BULLISH" else "BEARISH", fontWeight = FontWeight.Bold, color = color, fontSize = 14.sp)
                }
                Text(session, color = UiColors.TextSecondary, fontSize = 12.sp)
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(confidence, fontWeight = FontWeight.Bold, color = UiColors.TextPrimary, fontSize = 14.sp)
            Text(date, color = UiColors.TextSecondary, fontSize = 12.sp)
        }
    }
}

@Composable
fun JournalCard(isBullish: Boolean, tf: String, session: String, confidence: String, price: String, date: String, onDelete: (() -> Unit)? = null) {
    val color = if (isBullish) UiColors.BullishGreen else UiColors.BearishRed
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(UiColors.Surface, RoundedCornerShape(12.dp))
            .border(1.dp, UiColors.SurfaceLight, RoundedCornerShape(12.dp))
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(8.dp).background(color, CircleShape))
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Row {
                    Text(if (isBullish) "BULLISH" else "BEARISH", fontWeight = FontWeight.Bold, color = color, fontSize = 14.sp)
                    Text(" • $tf • $session", color = UiColors.TextSecondary, fontSize = 14.sp)
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(date, color = UiColors.TextSecondary, fontSize = 12.sp)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(horizontalAlignment = Alignment.End) {
                Text(confidence, fontWeight = FontWeight.Bold, color = UiColors.TextPrimary, fontSize = 16.sp)
                Spacer(modifier = Modifier.height(4.dp))
                Text(price, color = UiColors.TextSecondary, fontSize = 12.sp)
            }
            if (onDelete != null) {
                Spacer(modifier = Modifier.width(16.dp))
                Icon(
                    Icons.Default.DeleteOutline, 
                    contentDescription = "Delete", 
                    tint = UiColors.TextSecondary, 
                    modifier = Modifier.size(20.dp).clickable { onDelete() }
                )
            }
        }
    }
}

@Composable
fun FilterChip(text: String, isSelected: Boolean) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (isSelected) UiColors.PrimaryYellow else UiColors.SurfaceLight)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clickable { /* state handling could be added */ }
    ) {
        Text(text, color = if (isSelected) Color.Black else UiColors.TextSecondary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsTab(viewModel: TradingBotViewModel) {
    var twelveKey by remember { mutableStateOf(viewModel.settings.twelveApiKey) }
    var deepseekKey by remember { mutableStateOf(viewModel.settings.deepseekApiKey) }
    var isSaved by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text("API Keys Configuration", color = UiColors.PrimaryYellow, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            Text("Kunci rahasia ini disimpan secara aman di memori HP Anda (SharedPreferences).", color = UiColors.TextSecondary, fontSize = 14.sp)
        }

        item {
            OutlinedTextField(
                value = twelveKey,
                onValueChange = { twelveKey = it },
                label = { Text("Twelve Data API Key") },
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = UiColors.TextPrimary,
                    unfocusedTextColor = UiColors.TextPrimary,
                    cursorColor = UiColors.PrimaryYellow,
                    focusedBorderColor = UiColors.PrimaryYellow,
                    unfocusedBorderColor = UiColors.TextSecondary
                )
            )
        }

        item {
            OutlinedTextField(
                value = deepseekKey,
                onValueChange = { deepseekKey = it },
                label = { Text("DeepSeek API Key") },
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = UiColors.TextPrimary,
                    unfocusedTextColor = UiColors.TextPrimary,
                    cursorColor = UiColors.PrimaryYellow,
                    focusedBorderColor = UiColors.PrimaryYellow,
                    unfocusedBorderColor = UiColors.TextSecondary
                )
            )
        }

        item {
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = {
                    viewModel.saveKeys(twelveKey, deepseekKey)
                    isSaved = true
                    viewModel.startBot()
                },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                colors = ButtonDefaults.buttonColors(containerColor = UiColors.PrimaryYellow)
            ) {
                Text("Save & Start Bot", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
            if (isSaved) {
                Spacer(modifier = Modifier.height(8.dp))
                Text("✅ Tersimpan! Bot mencoba untuk menyala...", color = UiColors.BullishGreen, fontSize = 14.sp)
            }
        }
    }
    }
}

@Composable
fun TerminalTab(viewModel: TradingBotViewModel) {
    val logs by viewModel.logs.collectAsState()
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Terminal, contentDescription = null, tint = UiColors.BullishGreen, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("BOT TERMINAL", color = UiColors.BullishGreen, fontSize = 16.sp, fontWeight = FontWeight.Bold, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
        }
        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider(color = UiColors.SurfaceLight)
        Spacer(modifier = Modifier.height(8.dp))
        
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(logs.size) { index ->
                Text(
                    text = "> ${logs[index]}",
                    color = UiColors.BullishGreen,
                    fontSize = 12.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
        }
    }
}
