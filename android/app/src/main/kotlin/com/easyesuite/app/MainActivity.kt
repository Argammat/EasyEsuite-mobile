package com.easyesuite.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.easyesuite.app.ui.AppRoot
import com.easyesuite.app.ui.theme.EasyEsuiteTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as EasyEsuiteApp).container
        setContent {
            EasyEsuiteTheme {
                AppRoot(container)
            }
        }
    }
}
