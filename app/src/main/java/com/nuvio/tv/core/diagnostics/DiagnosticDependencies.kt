package com.nuvio.tv.core.diagnostics

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Small UI bridge for screens that do not own a Hilt ViewModel. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface DiagnosticDependencies {
    fun reportStore(): DiagnosticReportStore
    fun shareController(): DiagnosticShareController
}