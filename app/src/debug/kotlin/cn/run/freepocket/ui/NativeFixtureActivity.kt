package cn.run.freepocket.ui

import android.app.Activity
import android.os.Bundle
import cn.run.freepocket.R

/** Diagnostic-only host: no account, coordinator, network discovery or order handling. */
class NativeFixtureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.native_pocket_theme)
        super.onCreate(savedInstanceState)
    }
}
