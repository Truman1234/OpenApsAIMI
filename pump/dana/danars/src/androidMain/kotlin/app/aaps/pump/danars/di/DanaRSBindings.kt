package app.aaps.pump.danars.di

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides

/**
 * The Bluetooth adapter, on Metro. Was part of `DanaRSModule` + `DanaRSCommModule`.
 *
 * The DanaRS command set (`Set<DanaRSPacket>`) moved to commonMain's [DanaRSCommonBindings]: it
 * has no Android-specific part, and iOS needs it too (see that file's doc).
 */
@ContributesTo(AppScope::class)
@BindingContainer
object DanaRSBindings {

    @Provides
    fun providesBluetoothAdapter(context: Context): BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?)?.adapter
}
