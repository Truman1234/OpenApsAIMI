package app.aaps.pump.danars.compose

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.banner.ErrorBanner
import app.aaps.core.ui.compose.pump.WizardButton
import app.aaps.core.ui.compose.pump.WizardScreen
import app.aaps.core.ui.compose.pump.WizardStepLayout
import app.aaps.core.ui.compose.stringResource
import app.aaps.pump.dana.DanaStrings

/**
 * Same wizard shell as [DanaRsPairWizardScreen], for [DanaRsRefillWizardModel]. [model] is a plain
 * class, held by the caller with `remember` - see that model's doc.
 */
@Composable
fun DanaRsRefillWizardScreen(
    model: DanaRsRefillWizardModel,
    onFinish: () -> Unit,
    onCancel: () -> Unit
) {
    val uiState by model.uiState.collectAsState()

    val stepIndex = when (uiState.step) {
        DanaRsRefillWizardStep.RESERVOIR_AMOUNT -> 0
        DanaRsRefillWizardStep.PRIME_TUBE        -> 1
        DanaRsRefillWizardStep.PRIME_CANNULA     -> 2
        DanaRsRefillWizardStep.COMPLETE          -> 3
        // Stay on the progress dot of the step that actually failed.
        DanaRsRefillWizardStep.ERROR             -> when (uiState.failedStep) {
            DanaRsRefillWizardStep.PRIME_TUBE    -> 1
            DanaRsRefillWizardStep.PRIME_CANNULA -> 2
            else                                  -> 0
        }
    }

    // Mid-prime (insulin already requested, outcome not known yet) is the one moment leaving is unsafe.
    val canGoBack = uiState.primeState != DanaRsPrimeState.PRIMING

    WizardScreen(
        currentStep = uiState.step,
        totalSteps = 4,
        currentStepIndex = stepIndex,
        canGoBack = canGoBack,
        onBack = onCancel,
        cancelDialogTitle = stringResource(DanaStrings.cancel_refill_prime_title),
        cancelDialogText = stringResource(DanaStrings.cancel_refill_prime_message)
    ) { step, onCancelDialog ->
        when (step) {
            DanaRsRefillWizardStep.RESERVOIR_AMOUNT -> ReservoirAmountStep(
                amount = uiState.reservoirAmount,
                onAmountChange = model::updateReservoirAmount,
                onSubmit = model::submitReservoirAmount,
                onCancel = onCancelDialog
            )

            DanaRsRefillWizardStep.PRIME_TUBE        -> PrimeStep(
                titleRes = DanaStrings.danars_prime_tube_title,
                messageRes = DanaStrings.danars_prime_tube_message,
                primeState = uiState.primeState,
                amount = uiState.tubeAmount,
                onAmountChange = model::updateTubeAmount,
                onStartPrime = model::startPrimeTube,
                onNext = model::nextAfterTube,
                onCancel = onCancelDialog
            )

            DanaRsRefillWizardStep.PRIME_CANNULA     -> PrimeStep(
                titleRes = DanaStrings.danars_prime_cannula_title,
                messageRes = DanaStrings.danars_prime_cannula_message,
                primeState = uiState.primeState,
                amount = uiState.cannulaAmount,
                onAmountChange = model::updateCannulaAmount,
                onStartPrime = model::startPrimeCannula,
                onNext = model::nextAfterCannula,
                onCancel = onCancelDialog
            )

            DanaRsRefillWizardStep.COMPLETE          -> RefillPrimeCompleteStep(
                onDone = {
                    model.finishWizard()
                    onFinish()
                }
            )

            DanaRsRefillWizardStep.ERROR             -> RefillPrimeErrorStep(
                errorMessage = uiState.errorMessage,
                onRetry = model::retry,
                onCancel = onCancelDialog
            )
        }
    }
}

@Composable
private fun ReservoirAmountStep(
    amount: String,
    onAmountChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onCancel: () -> Unit
) {
    WizardStepLayout(
        primaryButton = WizardButton(
            text = stringResource(CoreUiStrings.next),
            onClick = onSubmit,
            enabled = amount.toIntOrNull() != null
        ),
        secondaryButton = WizardButton(
            text = stringResource(CoreUiStrings.cancel),
            onClick = onCancel
        )
    ) {
        Text(
            text = stringResource(DanaStrings.danars_reservoir_amount_message),
            style = MaterialTheme.typography.bodyLarge
        )
        OutlinedTextField(
            value = amount,
            onValueChange = onAmountChange,
            label = { Text(stringResource(DanaStrings.danars_reservoir_amount_step_title)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun PrimeStep(
    titleRes: TextRef,
    messageRes: TextRef,
    primeState: DanaRsPrimeState,
    amount: String,
    onAmountChange: (String) -> Unit,
    onStartPrime: () -> Unit,
    onNext: () -> Unit,
    onCancel: () -> Unit
) {
    WizardStepLayout(
        primaryButton = when (primeState) {
            DanaRsPrimeState.READY   -> WizardButton(
                text = stringResource(CoreUiStrings.next),
                onClick = onStartPrime,
                enabled = amount.toDoubleOrNull()?.let { it > 0.0 } == true
            )

            DanaRsPrimeState.PRIMING -> null
            DanaRsPrimeState.DONE    -> WizardButton(text = stringResource(CoreUiStrings.next), onClick = onNext)
        },
        secondaryButton = if (primeState != DanaRsPrimeState.PRIMING) WizardButton(
            text = stringResource(CoreUiStrings.cancel),
            onClick = onCancel
        ) else null
    ) {
        Text(
            text = stringResource(titleRes),
            style = MaterialTheme.typography.headlineSmall
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(messageRes),
            style = MaterialTheme.typography.bodyLarge
        )

        when (primeState) {
            DanaRsPrimeState.READY   -> OutlinedTextField(
                value = amount,
                onValueChange = onAmountChange,
                label = { Text(stringResource(DanaStrings.danars_prime_amount_hint)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            DanaRsPrimeState.PRIMING -> {
                Spacer(Modifier.height(32.dp))
                Text(
                    text = stringResource(DanaStrings.danars_prime_in_progress),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(16.dp))
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(64.dp)
                        .align(Alignment.CenterHorizontally)
                )
            }

            DanaRsPrimeState.DONE    -> Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = null,
                modifier = Modifier
                    .size(48.dp)
                    .align(Alignment.CenterHorizontally),
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun RefillPrimeCompleteStep(
    onDone: () -> Unit
) {
    WizardStepLayout(
        primaryButton = WizardButton(
            text = stringResource(DanaStrings.done),
            onClick = onDone
        )
    ) {
        Spacer(modifier = Modifier.height(48.dp))
        Icon(
            imageVector = Icons.Filled.CheckCircle,
            contentDescription = null,
            modifier = Modifier
                .size(80.dp)
                .align(Alignment.CenterHorizontally),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(DanaStrings.danars_refill_prime_complete_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = stringResource(DanaStrings.danars_refill_prime_complete_message),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun RefillPrimeErrorStep(
    errorMessage: String?,
    onRetry: () -> Unit,
    onCancel: () -> Unit
) {
    WizardStepLayout(
        primaryButton = WizardButton(
            text = stringResource(CoreUiStrings.retry),
            onClick = onRetry
        ),
        secondaryButton = WizardButton(
            text = stringResource(CoreUiStrings.cancel),
            onClick = onCancel
        )
    ) {
        Spacer(modifier = Modifier.height(24.dp))
        errorMessage?.let { ErrorBanner(message = it) }
            ?: ErrorBanner(message = stringResource(CoreUiStrings.error))
    }
}
