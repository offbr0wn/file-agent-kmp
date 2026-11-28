package dev.fileagent.app

import android.app.Activity
import kotlinx.coroutines.flow.StateFlow

/** What people can buy for File Agent AI. */
enum class Product { Plus, TopUp }

/**
 * Where payments happen. The GitHub version opens Stripe checkout pages; the Play version uses
 * Google Play Billing (Play requires it for in-app purchases). Each version has its own
 * `makeStore` in its source folder.
 */
interface Store {
    /** Prices to show, as the store formats them (e.g. "£4.99"). A product is missing until it can be bought. */
    val prices: StateFlow<Map<Product, String>>

    /** True when payments can be offered at all (links set up, or Play products loaded). */
    fun ready(product: Product, hosted: HostedState): Boolean

    /** Starts a purchase. Returns a short message for the user, or null when the store took over. */
    suspend fun buy(activity: Activity, product: Product, hosted: HostedState): String?

    /**
     * Loads prices and finishes any purchase left half-done (app closed mid-payment, a reinstall).
     * [onChange] gets a message for the user, or null, whenever the balance may have changed.
     */
    fun sync(onChange: (String?) -> Unit)
}
