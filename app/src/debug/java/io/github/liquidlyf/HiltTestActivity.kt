package io.github.liquidlyf

import androidx.activity.ComponentActivity
import dagger.hilt.android.AndroidEntryPoint

/**
 * Debug builds only: an empty Hilt activity that instrumented Compose tests put their
 * content in (createAndroidComposeRule<HiltTestActivity>()), so screens can use hiltViewModel().
 */
@AndroidEntryPoint
class HiltTestActivity : ComponentActivity()
