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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.banner.ErrorBanner
import app.aaps.core.ui.compose.pump.BleScanStep
import app.aaps.core.ui.compose.pump.WizardButton
import app.aaps.core.ui.compose.pump.WizardScreen
import app.aaps.core.ui.compose.pump.WizardStepLayout
import app.aaps.core.ui.compose.stringResource
import app.aaps.pump.dana.DanaStrings

/**
 * Same layout as Android's `DanaRSPairWizardScreen`, built on the shared commonMain wizard
 * primitives. [model] is a plain class, not a `ViewModel` - see [DanaRsPairWizardModel]'s doc for
 * why - so the caller holds it with `remember`, same as `IosMedtrumPlugin`'s wizard screen.
 */
@Composable
fun DanaRsPairWizardScreen(
    model: DanaRsPairWizardModel,
    onFinish: () -> Unit,
    onCancel: () -> Unit
) {
    val uiState by model.uiState.collectAsState()

    val stepIndex = when (uiState.step) {
        DanaRsWizardStep.BLE_SCAN         -> 0
        DanaRsWizardStep.PAIRING_PROGRESS -> 1
        DanaRsWizardStep.ENTER_PASSWORD   -> 1
        DanaRsWizardStep.ENTER_PIN        -> 1
        DanaRsWizardStep.COMPLETE         -> 2
        DanaRsWizardStep.ERROR            -> 1
    }

    WizardScreen(
        currentStep = uiState.step,
        totalSteps = 3,
        currentStepIndex = stepIndex,
        canGoBack = uiState.step == DanaRsWizardStep.BLE_SCAN,
        onBack = onCancel,
        cancelDialogTitle = stringResource(DanaStrings.cancel_pairing_title),
        cancelDialogText = stringResource(DanaStrings.cancel_pairing_message)
    ) { step, onCancelDialog ->
        when (step) {
            DanaRsWizardStep.BLE_SCAN         -> BleScanStep(
                devices = uiState.devices,
                onSelectDevice = model::selectDevice,
                onStartScan = model::startScan,
                onCancel = onCancelDialog
            )

            DanaRsWizardStep.PAIRING_PROGRESS -> PairingProgressStep(
                onCancel = onCancel
            )

            DanaRsWizardStep.ENTER_PASSWORD   -> EnterPasswordStep(
                password = uiState.password,
                onPasswordChange = model::updatePassword,
                onSubmit = model::submitPassword,
                onCancel = onCancel
            )

            DanaRsWizardStep.ENTER_PIN        -> EnterPinStep(
                pin1 = uiState.pin1,
                pin2 = uiState.pin2,
                onPin1Change = model::updatePin1,
                onPin2Change = model::updatePin2,
                onSubmit = model::submitPin,
                onCancel = onCancel,
                errorMessage = uiState.errorMessage
            )

            DanaRsWizardStep.COMPLETE         -> PairingCompleteStep(
                onDone = {
                    model.finishWizard()
                    onFinish()
                }
            )

            DanaRsWizardStep.ERROR            -> PairingErrorStep(
                errorMessage = uiState.errorMessage,
                onRetry = model::retry,
                onCancel = onCancel
            )
        }
    }
}

@Composable
private fun PairingProgressStep(
    onCancel: () -> Unit
) {
    WizardStepLayout(
        secondaryButton = WizardButton(
            text = stringResource(CoreUiStrings.cancel),
            onClick = onCancel
        )
    ) {
        Spacer(modifier = Modifier.height(48.dp))
        CircularProgressIndicator(
            modifier = Modifier
                .size(64.dp)
                .align(Alignment.CenterHorizontally)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(DanaStrings.connecting_to_pump),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun EnterPasswordStep(
    password: String,
    onPasswordChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onCancel: () -> Unit
) {
    WizardStepLayout(
        primaryButton = WizardButton(
            text = stringResource(CoreUiStrings.ok),
            onClick = onSubmit,
            enabled = password.length == 4
        ),
        secondaryButton = WizardButton(
            text = stringResource(CoreUiStrings.cancel),
            onClick = onCancel
        )
    ) {
        Text(
            text = stringResource(DanaStrings.wrongpumppassword),
            style = MaterialTheme.typography.bodyLarge
        )

        OutlinedTextField(
            value = password,
            onValueChange = { input -> input.filter { it.isHexChar() }.take(4).uppercase().let(onPasswordChange) },
            label = { Text(stringResource(DanaStrings.danars_password_title)) },
            supportingText = { Text(stringResource(CoreUiStrings.error_mustbe4hexadidits)) },
            keyboardOptions = hexKeyboardOptions,
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun EnterPinStep(
    pin1: String,
    pin2: String,
    onPin1Change: (String) -> Unit,
    onPin2Change: (String) -> Unit,
    onSubmit: () -> Unit,
    onCancel: () -> Unit,
    errorMessage: String?
) {
    WizardStepLayout(
        primaryButton = WizardButton(
            text = stringResource(CoreUiStrings.ok),
            onClick = onSubmit,
            enabled = pin1.length == 12 && pin2.length == 8
        ),
        secondaryButton = WizardButton(
            text = stringResource(CoreUiStrings.cancel),
            onClick = onCancel
        )
    ) {
        Text(
            text = stringResource(DanaStrings.press_ok_on_the_pump),
            style = MaterialTheme.typography.bodyLarge
        )

        errorMessage?.let { ErrorBanner(message = it) }

        OutlinedTextField(
            value = pin1,
            onValueChange = { input -> input.filter { it.isHexChar() }.take(12).uppercase().let(onPin1Change) },
            label = { Text(stringResource(DanaStrings.num1pin)) },
            keyboardOptions = hexKeyboardOptions,
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = pin2,
            onValueChange = { input -> input.filter { it.isHexChar() }.take(8).uppercase().let(onPin2Change) },
            label = { Text(stringResource(DanaStrings.num2pin)) },
            keyboardOptions = hexKeyboardOptions,
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun PairingCompleteStep(
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
            text = stringResource(DanaStrings.pairing_successful),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = stringResource(DanaStrings.pairing_successful_message),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun PairingErrorStep(
    errorMessage: String?,
    onRetry: () -> Unit,
    onCancel: () -> Unit
) {
    WizardStepLayout(
        primaryButton = WizardButton(
            text = stringResource(CoreUiStrings.pairing),
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

private val hexKeyboardOptions = KeyboardOptions(
    keyboardType = KeyboardType.Ascii,
    capitalization = KeyboardCapitalization.Characters
)

private fun Char.isHexChar(): Boolean =
    this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
