package com.yzddmr6.prismspace.setup

import android.app.Activity
import android.content.Context
import android.content.Intent

object SetupFlow {
    fun open(context: Context) {
        val intent = Intent(context, SetupActivity::class.java)
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
