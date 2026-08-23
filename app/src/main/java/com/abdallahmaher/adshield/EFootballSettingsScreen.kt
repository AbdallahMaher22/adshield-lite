package com.abdallahmaher.adshield

import android.content.Context
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun EFootballSettingsScreen(context: Context, onBack: () -> Unit) {
    var enabled by remember { mutableStateOf(PreferencesRepository.isEFootballEnabled(context)) }
    var selectedApps by remember { mutableStateOf(PreferencesRepository.getEFootballTargetApps(context)) }
    val apps = remember { AppListUtils.getLaunchableApps(context) }

    Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
        Text("خاص بـ eFootball™", style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "خاصية بتحجب سيرفرات الـ Load Balancer اللي بتسبب لاج، وبتوجّه سيرفر اللعبة الرئيسي لعنوان IP ثابت. " +
                "تقدر تحدد تطبيق أو أكتر تتطبق عليهم بس.",
            style = MaterialTheme.typography.bodySmall
        )

        Spacer(modifier = Modifier.height(20.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("تفعيل الخاصية")
            Switch(
                checked = enabled,
                onCheckedChange = {
                    enabled = it
                    PreferencesRepository.setEFootballEnabled(context, it)
                }
            )
        }

        Spacer(modifier = Modifier.height(20.dp))
        Text("التطبيقات المستهدفة:", style = MaterialTheme.typography.titleSmall)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                "تحديد تطبيق بعينه متاح على أندرويد 10 فأحدث فقط. على النسخة اللي عندك، " +
                    "تفعيل الخاصية هيطبقها بشكل عام بدل ما يقتصر على التطبيقات المختارة.",
                style = MaterialTheme.typography.bodySmall
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(apps) { app ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = selectedApps.contains(app.packageName),
                        onCheckedChange = { checked ->
                            selectedApps = if (checked) {
                                selectedApps + app.packageName
                            } else {
                                selectedApps - app.packageName
                            }
                            PreferencesRepository.setEFootballTargetApps(context, selectedApps)
                        }
                    )
                    Column {
                        Text(app.label, style = MaterialTheme.typography.bodyMedium)
                        Text(app.packageName, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        Text(
            "ملحوظة: لو غيرت الإعدادات دي والحماية شغالة بالفعل، وقّفها وشغّلها تاني عشان التغيير ياخد مفعوله.",
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(modifier = Modifier.height(12.dp))
        Button(onClick = onBack) { Text("رجوع") }
    }
}
