plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.roborazzi)
  alias(libs.plugins.secrets)
}

android {
  namespace = "com.example"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.aistudio.aitradingbot.dkajsn"
    minSdk = 24
    targetSdk = 36
    versionCode = 1
    versionName = "1.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
      storeFile = file(keystorePath)
      storePassword = System.getenv("STORE_PASSWORD")
      keyAlias = "upload"
      keyPassword = System.getenv("KEY_PASSWORD")
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("release")
    }
    debug {
      // signingConfig = signingConfigs.getByName("debugConfig")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
}

secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
}

tasks.named("preBuild") {
  doFirst {
    val dashboardFile = file("src/main/java/com/example/ui/dashboard/DashboardScreen.kt")
    if (dashboardFile.exists()) {
      val original = dashboardFile.readText()
      var patched = original
        .replace(
          """LaunchedEffect(Unit) {
        viewModel.startBot()
    }""",
          """LaunchedEffect(Unit) {
        if (viewModel.settings.areKeysSet()) {
            viewModel.startBot()
        } else {
            viewModel.log("Menunggu API Key di Settings.")
        }
    }"""
        )
        .replace(
          """Text("Save & Start Bot", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 16.sp)""",
          """Text(if (viewModel.settings.areKeysSet()) "API Keys Tersimpan" else "Simpan API Keys", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 16.sp)"""
        )
        .replace(
          """Text("✅ Tersimpan! Bot mencoba untuk menyala...", color = UiColors.BullishGreen, fontSize = 14.sp)""",
          """Text("✅ API Keys tersimpan. CandleBuilder akan berjalan setelah key valid.", color = UiColors.BullishGreen, fontSize = 14.sp)"""
        )
        .replace(
          """val killZones = listOf("Asian Kill Zone", "London Open Kill Zone", "New York Kill Zone", "Silver Bullet")""",
          """val killZones = listOf("Asian Kill Zone", "London Open Kill Zone", "London Close Kill Zone", "New York Open Kill Zone", "Silver Bullet (10:00-11:00 NY)", "Di Luar Sesi")"""
        )

      if (!patched.contains("ICT DETAIL")) {
        patched = patched.replace(
          """AnalysisRow("Risk / Reward", ts?.optString("risk_reward", "-") ?: "-")""",
          """AnalysisRow("Risk / Reward", ts?.optString("risk_reward", "-") ?: "-")
                        AnalysisRow("Invalidation", ts?.optString("invalidation", "-") ?: "-")

                        val orderBlocks = json.optJSONObject("order_blocks")
                        val fvgObject = json.optJSONObject("fvg")
                        val liquidityObject = json.optJSONObject("liquidity")
                        val pdObject = json.optJSONObject("premium_discount")
                        if (orderBlocks != null || fvgObject != null || liquidityObject != null || pdObject != null) {
                            Spacer(modifier = Modifier.height(12.dp))
                            HorizontalDivider(color = UiColors.SurfaceLight)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("ICT DETAIL", color = UiColors.PrimaryYellow, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                            Spacer(modifier = Modifier.height(8.dp))
                            orderBlocks?.let {
                                AnalysisRow("Bullish OB", it.optString("bullish_ob", "-"))
                                AnalysisRow("Bearish OB", it.optString("bearish_ob", "-"))
                                AnalysisRow("OB Notes", it.optString("description", "-"))
                            }
                            fvgObject?.let {
                                AnalysisRow("Bullish FVG", it.optString("bullish_fvg", "-"))
                                AnalysisRow("Bearish FVG", it.optString("bearish_fvg", "-"))
                                AnalysisRow("FVG Notes", it.optString("description", "-"))
                            }
                            liquidityObject?.let {
                                AnalysisRow("Buy Side", it.optString("buy_side", "-"))
                                AnalysisRow("Sell Side", it.optString("sell_side", "-"))
                                AnalysisRow("Sweep", if (it.optBoolean("sweep_occurred", false)) "YES" else "NO")
                                AnalysisRow("Liquidity Notes", it.optString("description", "-"))
                            }
                            pdObject?.let {
                                AnalysisRow("Equilibrium", formatTradeValue(it.opt("equilibrium")))
                                AnalysisRow("Current Zone", it.optString("current_zone", "-"))
                                AnalysisRow("OTE Zone", it.optString("ote_zone", "-"))
                            }
                        }

                        val keyNotes = json.optJSONArray("key_notes")
                        if (keyNotes != null && keyNotes.length() > 0) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Text("KEY NOTES", color = UiColors.PrimaryYellow, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                            for (i in 0 until keyNotes.length()) {
                                AnalysisRow("Note ${'$'}{i + 1}", keyNotes.optString(i, "-"))
                            }
                        }
                        val warnings = json.optJSONArray("warnings")
                        if (warnings != null && warnings.length() > 0) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("WARNINGS", color = UiColors.BearishRed, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                            for (i in 0 until warnings.length()) {
                                AnalysisRow("Warning ${'$'}{i + 1}", warnings.optString(i, "-"))
                            }
                        }"""
        )
      }

      if (patched != original) {
        dashboardFile.writeText(patched)
      }
    }

    val candleBuilderFile = file("src/main/java/com/example/data/network/CandleBuilder.kt")
    if (candleBuilderFile.exists()) {
      val original = candleBuilderFile.readText()
      val patched = original
        .replace("\"M15\" to 900L,\n        \"H1\"", "\"M15\" to 900L,\n        \"M30\" to 1800L,\n        \"H1\"")
        .replace("\"D1\" to 86400L", "\"D1\" to 86400L,\n        \"W1\" to 604800L")
      if (patched != original) {
        candleBuilderFile.writeText(patched)
      }
    }

    val deepSeekFile = file("src/main/java/com/example/data/network/DeepSeekClient.kt")
    if (deepSeekFile.exists()) {
      val target = """package com.example.data.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DeepSeekClient(private val apiKey: String) {
    suspend fun analyzeChart(prompt: String): String = withContext(Dispatchers.Default) {
        LocalSmcEngine.compile(prompt)
    }
}
"""
      if (deepSeekFile.readText() != target) {
        deepSeekFile.writeText(target)
      }
    }

    val manifestFile = file("src/main/AndroidManifest.xml")
    if (manifestFile.exists()) {
      val originalManifest = manifestFile.readText()
      val patchedManifest = originalManifest
        .replace("android:icon=\"@mipmap/ic_launcher\"", "android:icon=\"@drawable/ic_launcher_xau\"")
        .replace("android:roundIcon=\"@mipmap/ic_launcher_round\"", "android:roundIcon=\"@drawable/ic_launcher_xau\"")
      if (patchedManifest != originalManifest) {
        manifestFile.writeText(patchedManifest)
      }
    }

    val viewModelFile = file("src/main/java/com/example/ui/dashboard/TradingBotViewModel.kt")
    if (viewModelFile.exists()) {
      val original = viewModelFile.readText()
      var patched = original
        .replace("import com.example.data.network.CandleBuilder\nimport com.example.data.database.CandleEntity", "import com.example.data.network.CandleBuilder\nimport com.example.data.network.MarketEventScanner\nimport com.example.data.database.CandleEntity")
        .replace("private var m1ClosedCount = 0", "private var m1ClosedCount = 0\n    private val emittedMarketEventKeys = mutableSetOf<String>()")
        .replace("\"M1\", \"M5\", \"M15\", \"H1\", \"H4\", \"D1\"", "\"M1\", \"M5\", \"M15\", \"M30\", \"H1\", \"H4\", \"D1\", \"W1\"")
        .replace("\"M15\" -> 900L\n            \"H1\"", "\"M15\" -> 900L\n            \"M30\" -> 1800L\n            \"H1\"")
        .replace("\"D1\" -> 86400L", "\"D1\" -> 86400L\n            \"W1\" -> 604800L")
        .replace("""4. Berikan setup hanya jika valid. Jika belum valid, tulis wait.""", """4. Berikan setup hanya jika valid. Jika belum valid, tulis wait.
5. Sertakan market_structure, order_blocks, fvg, liquidity, premium_discount, trade_setup, key_notes, dan warnings.""")
        .replace("""db.candleDao().insert(entity)

                    if (candle.timeframe != "M1") {""", """db.candleDao().insert(entity)
                    scanAndLogMarketEvents(candle.timeframe, candle.close)

                    if (candle.timeframe != "M1") {""")
        .replace("""log("Real CandleBuilder multi-timeframe aktif. AI hanya berjalan saat tombol Analisis ICT Sekarang diklik.")""", """log("Market Event Scanner aktif: BOS, MSS, CISD, liquidity, FVG, OB, OTE, session, dan displacement akan muncul di Terminal.")""")

      if (!patched.contains("private suspend fun scanAndLogMarketEvents")) {
        patched = patched.replace(
          """    private suspend fun fetchAndSaveHistoricalCandles() {""",
          """    private suspend fun scanAndLogMarketEvents(timeframe: String, latestPrice: Double) {
        val target = normalizeTimeframe(timeframe)
        if (target == "M1" && m1ClosedCount % 5 != 0) return
        val recent = db.candleDao().getRecentCandles("XAU/USD", target, 180).reversed()
        if (recent.size < 8) return
        val events = MarketEventScanner.scan(target, recent, latestPrice)
        events.filter { it.priority >= 78 }.forEach { event ->
            if (emittedMarketEventKeys.add(event.key)) {
                log("EVENT ${'$'}{event.text}")
            }
        }
        if (emittedMarketEventKeys.size > 500) {
            emittedMarketEventKeys.clear()
            events.take(20).forEach { emittedMarketEventKeys.add(it.key) }
        }
    }

    private suspend fun fetchAndSaveHistoricalCandles() {"""
        )
      }

      if (!patched.contains("\"order_blocks\"")) {
        val oldSchema = """
    "risk_reward": "rasio"
  },
  "market_structure": {
    "trend": "Bullish/Bearish/Range",
    "liquidity": "ringkasan",
    "fvg": "ringkasan",
    "order_block": "ringkasan",
    "premium_discount": "ringkasan"
  }
""".trimIndent()
        val newSchema = """
    "risk_reward": "rasio",
    "invalidation": "level invalidasi"
  },
  "market_structure": {
    "trend": "Bullish/Bearish/Range",
    "last_bos": "BOS terakhir",
    "choch": "CHoCH/MSS terakhir",
    "swing_high": 0.0,
    "swing_low": 0.0,
    "liquidity": "ringkasan",
    "fvg": "ringkasan",
    "order_block": "ringkasan",
    "premium_discount": "ringkasan"
  },
  "order_blocks": {
    "bullish_ob": "area bullish OB",
    "bearish_ob": "area bearish OB",
    "description": "catatan OB"
  },
  "fvg": {
    "bullish_fvg": "area bullish FVG",
    "bearish_fvg": "area bearish FVG",
    "description": "catatan FVG"
  },
  "liquidity": {
    "buy_side": "BSL terdekat",
    "sell_side": "SSL terdekat",
    "sweep_occurred": false,
    "description": "catatan liquidity"
  },
  "premium_discount": {
    "equilibrium": 0.0,
    "current_zone": "PREMIUM/DISCOUNT/EQUILIBRIUM",
    "ote_zone": "62-79% zone"
  },
  "key_notes": ["catatan penting"],
  "warnings": ["peringatan risiko"]
""".trimIndent()
        patched = patched.replace(oldSchema, newSchema)
      }

      if (patched != original) {
        viewModelFile.writeText(patched)
      }
    }
  }
}

dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(platform(libs.firebase.bom))
  // implementation(libs.accompanist.permissions)
  implementation(libs.androidx.activity.compose)
  // implementation(libs.androidx.camera.camera2)
  // implementation(libs.androidx.camera.core)
  // implementation(libs.androidx.camera.lifecycle)
  // implementation(libs.androidx.camera.view)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  // implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  implementation(libs.coil.compose)
  implementation(libs.converter.moshi)
  // implementation(libs.firebase.ai)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.logging.interceptor)
  implementation(libs.moshi.kotlin)
  implementation(libs.okhttp)
  // implementation(libs.play.services.location)
  implementation(libs.retrofit)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  testImplementation(libs.roborazzi)
  testImplementation(libs.roborazzi.compose)
  testImplementation(libs.roborazzi.junit.rule)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  "ksp"(libs.androidx.room.compiler)
  "ksp"(libs.moshi.kotlin.codegen)
}
