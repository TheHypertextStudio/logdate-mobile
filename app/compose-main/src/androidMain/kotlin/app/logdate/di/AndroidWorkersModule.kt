package app.logdate.di

import app.logdate.client.ambient.AmbientPromptWorker
import app.logdate.client.calendar.CalendarImportWorker
import app.logdate.client.events.EventInferenceWorker
import app.logdate.client.restore.NativeMediaQuarantineWorker
import app.logdate.client.rewind.RewindGenerationWorker
import app.logdate.client.shortcuts.DynamicShortcutRefreshWorker
import app.logdate.feature.core.export.CloudBackupWorker
import org.koin.androidx.workmanager.dsl.workerOf
import org.koin.core.module.Module
import org.koin.dsl.module

internal val androidWorkersModule: Module =
    module {
        workerOf(::AmbientPromptWorker)
        workerOf(::RewindGenerationWorker)
        workerOf(::EventInferenceWorker)
        workerOf(::CalendarImportWorker)
        workerOf(::CloudBackupWorker)
        workerOf(::NativeMediaQuarantineWorker)
        workerOf(::DynamicShortcutRefreshWorker)
    }
