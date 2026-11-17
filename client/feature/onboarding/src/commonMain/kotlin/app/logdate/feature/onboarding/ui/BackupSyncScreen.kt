@file:Suppress("ktlint:standard:function-naming", "ktlint:standard:max-line-length")

package app.logdate.feature.onboarding.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.logdate.client.billing.model.LogDateBackupPlanOption
import app.logdate.ui.adaptive.FoldableBookLayout
import app.logdate.ui.adaptive.FoldableTabletopLayout
import app.logdate.ui.theme.Spacing
import logdate.client.feature.onboarding.generated.resources.Res
import logdate.client.feature.onboarding.generated.resources.onboarding_cloud_backup_description
import logdate.client.feature.onboarding.generated.resources.onboarding_plan_basic_description
import logdate.client.feature.onboarding.generated.resources.onboarding_plan_basic_price
import logdate.client.feature.onboarding.generated.resources.onboarding_plan_basic_title
import logdate.client.feature.onboarding.generated.resources.onboarding_plan_premium_description
import logdate.client.feature.onboarding.generated.resources.onboarding_plan_premium_price
import logdate.client.feature.onboarding.generated.resources.onboarding_plan_premium_title
import logdate.client.feature.onboarding.generated.resources.section_title_backup_sync
import logdate.client.ui.generated.resources.common_back
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import logdate.client.ui.generated.resources.Res as UiRes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupSyncScreenContent(
    useCompactLayout: Boolean,
    onBack: () -> Unit,
    onPlanSelected: (option: LogDateBackupPlanOption) -> Unit,
) {
    if (useCompactLayout) {
        BackupSyncCompactContent(
            onBack = onBack,
            onPlanSelected = onPlanSelected,
        )
    } else {
        BackupSyncAdaptiveContent(
            onBack = onBack,
            onPlanSelected = onPlanSelected,
        )
    }
}

@Composable
private fun BackupSyncAdaptiveContent(
    onBack: () -> Unit,
    onPlanSelected: (option: LogDateBackupPlanOption) -> Unit,
) {
    FoldableTabletopLayout(
        modifier = Modifier.fillMaxSize(),
        minPaneHeight = 260.dp,
        topPane = {
            BackupSyncIntroPane(
                onBack = onBack,
                modifier = Modifier.fillMaxSize(),
            )
        },
        bottomPane = {
            BackupSyncPlanPane(
                onPlanSelected = onPlanSelected,
                modifier = Modifier.fillMaxSize(),
            )
        },
        standardContent = {
            FoldableBookLayout(
                modifier = Modifier.fillMaxSize(),
                minPaneWidth = 320.dp,
                startPane = {
                    BackupSyncIntroPane(
                        onBack = onBack,
                        modifier = Modifier.fillMaxSize(),
                    )
                },
                endPane = {
                    BackupSyncPlanPane(
                        onPlanSelected = onPlanSelected,
                        modifier = Modifier.fillMaxSize(),
                    )
                },
                standardContent = {
                    BackupSyncCompactContent(
                        onBack = onBack,
                        onPlanSelected = onPlanSelected,
                    )
                },
            )
        },
    )
}

@Composable
private fun BackupSyncIntroPane(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(Res.string.section_title_backup_sync)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Default.ArrowBack,
                            contentDescription = stringResource(UiRes.string.common_back),
                        )
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors().copy(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
            )
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier,
    ) { contentPadding ->
        Column(
            Modifier
                .padding(contentPadding)
                .padding(Spacing.lg),
        ) {
            Text(
                stringResource(
                    Res.string.onboarding_cloud_backup_description,
                ),
            )
        }
    }
}

@Composable
private fun BackupSyncPlanPane(
    onPlanSelected: (option: LogDateBackupPlanOption) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(Spacing.lg),
    ) {
        MainContent(
            onPlanSelected = onPlanSelected,
        )
    }
}

@Composable
private fun BackupSyncCompactContent(
    onBack: () -> Unit,
    onPlanSelected: (option: LogDateBackupPlanOption) -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(Res.string.section_title_backup_sync)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Default.ArrowBack,
                            contentDescription = stringResource(UiRes.string.common_back),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { contentPadding ->
        Column(
            modifier =
                Modifier
                    .padding(contentPadding)
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
        ) {
            Text(
                stringResource(
                    Res.string.onboarding_cloud_backup_description,
                ),
                modifier = Modifier.padding(horizontal = Spacing.lg),
            )
            MainContent(
                onPlanSelected = onPlanSelected,
            )
        }
    }
}

@Composable
private fun MainContent(
    onPlanSelected: (option: LogDateBackupPlanOption) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        items(LogDateBackupPlanOption.entries) { option ->
            PlanContentCard(
                option = option,
                title = stringResource(option.titleResource),
                description = stringResource(option.descriptionResource),
                price = stringResource(option.priceResource),
                onPlanSelected = onPlanSelected,
            )
        }
    }
}

private val LogDateBackupPlanOption.titleResource: StringResource
    get() =
        when (this) {
            LogDateBackupPlanOption.BASIC -> Res.string.onboarding_plan_basic_title
            LogDateBackupPlanOption.STANDARD -> Res.string.onboarding_plan_premium_title
        }

private val LogDateBackupPlanOption.descriptionResource: StringResource
    get() =
        when (this) {
            LogDateBackupPlanOption.BASIC -> Res.string.onboarding_plan_basic_description
            LogDateBackupPlanOption.STANDARD -> Res.string.onboarding_plan_premium_description
        }

private val LogDateBackupPlanOption.priceResource: StringResource
    get() =
        when (this) {
            LogDateBackupPlanOption.BASIC -> Res.string.onboarding_plan_basic_price
            LogDateBackupPlanOption.STANDARD -> Res.string.onboarding_plan_premium_price
        }

@Composable
internal fun PlanContentCard(
    option: LogDateBackupPlanOption,
    title: String,
    description: String,
    price: String,
    onPlanSelected: (option: LogDateBackupPlanOption) -> Unit,
) {
    Card(
        onClick = { onPlanSelected(option) },
        modifier = Modifier.padding(Spacing.lg),
    ) {
        Column {
            Column(
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(title)
                Text(description)
            }
            Text(price)
        }
    }
}

@Preview
@Composable
private fun BackupSyncScreenPreview_Compact() {
    BackupSyncScreenContent(
        useCompactLayout = true,
        onBack = {},
        onPlanSelected = {},
    )
}

@Preview
@Composable
private fun BackupSyncScreenPreview_Medium() {
    BackupSyncScreenContent(
        useCompactLayout = false,
        onBack = {},
        onPlanSelected = {},
    )
}
