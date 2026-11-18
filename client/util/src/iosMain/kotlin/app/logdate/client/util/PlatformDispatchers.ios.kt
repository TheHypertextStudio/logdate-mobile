package app.logdate.client.util

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

actual val platformIODispatcher: CoroutineDispatcher = Dispatchers.Default

actual val platformDataDispatcher: CoroutineDispatcher = platformIODispatcher
