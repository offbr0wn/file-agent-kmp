package dev.fileagent.app

import android.app.Activity
import android.app.Application
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClient.ProductType
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume

/** Play version: Google Play Billing. */
fun makeStore(app: Application, hosted: HostedAccount): Store = PlayStore(app, hosted)

/**
 * Plus is a monthly subscription, the top-up a one-time product bought again and again.
 * Each purchase goes to our server, which checks it with Google before adding messages.
 * Only then is it acknowledged (Plus) or used up (top-up), so nothing is lost if the app closes:
 * Google refunds purchases that are never acknowledged.
 */
private class PlayStore(app: Application, private val hosted: HostedAccount) : Store, PurchasesUpdatedListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val connecting = Mutex()
    private val _prices = MutableStateFlow<Map<Product, String>>(emptyMap())
    override val prices: StateFlow<Map<Product, String>> = _prices
    private var details: Map<Product, ProductDetails> = emptyMap()
    private var onChange: (String?) -> Unit = {}

    private val client = BillingClient.newBuilder(app)
        .setListener(this)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .build()

    override fun ready(product: Product, hosted: HostedState) = details.containsKey(product)

    override suspend fun buy(activity: Activity, product: Product, hosted: HostedState): String? {
        if (!connect()) return "Can't reach Google Play. Try again."
        if (details[product] == null) loadProducts()
        val pd = details[product] ?: return "This isn't on sale yet."
        runCatching { this.hosted.ensure() }.onFailure { return it.message ?: "Can't reach File Agent AI" }
        val item = BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(pd).apply {
            pd.subscriptionOfferDetails?.firstOrNull()?.offerToken?.let { setOfferToken(it) }
        }.build()
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(item))
            // Ties the purchase to this File Agent account without sending the key itself.
            .setObfuscatedAccountId(this.hosted.accountTag())
            .build()
        val r = client.launchBillingFlow(activity, params)
        return if (r.responseCode == BillingResponseCode.OK) null else "Google Play couldn't start the payment."
    }

    override fun sync(onChange: (String?) -> Unit) {
        this.onChange = onChange
        scope.launch {
            if (!connect()) return@launch
            loadProducts()
            // Finish anything left half-done, and bring an active Plus across after a reinstall.
            for (type in listOf(ProductType.SUBS, ProductType.INAPP)) {
                purchases(type).forEach { handle(it, quiet = true) }
            }
        }
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        when (result.responseCode) {
            BillingResponseCode.OK -> purchases.orEmpty().forEach { p -> scope.launch { handle(p, quiet = false) } }
            BillingResponseCode.USER_CANCELED -> Unit
            BillingResponseCode.ITEM_ALREADY_OWNED -> sync(onChange)
            else -> onChange("Payment didn't go through. You weren't charged.")
        }
    }

    private suspend fun handle(p: Purchase, quiet: Boolean) {
        if (p.purchaseState == Purchase.PurchaseState.PENDING) {
            if (!quiet) onChange("Payment is pending. Your messages arrive when it clears.")
            return
        }
        if (p.purchaseState != Purchase.PurchaseState.PURCHASED) return
        val id = p.products.firstOrNull() ?: return
        val sub = id == PLUS_ID
        // A top-up already used up, or a Plus already acknowledged and known, needs nothing on start.
        if (quiet && sub && p.isAcknowledged && hosted.lastPlayToken == p.purchaseToken) return
        val r = runCatching { hosted.verifyPlay(p.purchaseToken, id, if (sub) "subs" else "inapp") }
        if (r.isFailure) {
            if (!quiet) onChange("Paid, but we couldn't add your messages yet. They'll appear soon.")
            return
        }
        if (sub) {
            hosted.lastPlayToken = p.purchaseToken
            if (!p.isAcknowledged) client.acknowledgePurchase(
                AcknowledgePurchaseParams.newBuilder().setPurchaseToken(p.purchaseToken).build(),
            ) { }
        } else {
            client.consumeAsync(ConsumeParams.newBuilder().setPurchaseToken(p.purchaseToken).build()) { _, _ -> }
        }
        onChange(if (quiet) null else if (sub) "Plus is on. Thank you!" else "Top-up added. Thank you!")
    }

    private suspend fun connect(): Boolean = connecting.withLock {
        if (client.isReady) return true
        suspendCancellableCoroutine { cont ->
            client.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(r: BillingResult) {
                    if (cont.isActive) cont.resume(r.responseCode == BillingResponseCode.OK)
                }
                override fun onBillingServiceDisconnected() {
                    if (cont.isActive) cont.resume(false)
                }
            })
        }
    }

    private suspend fun loadProducts() {
        val found = mutableMapOf<Product, ProductDetails>()
        query(PLUS_ID, ProductType.SUBS)?.let { found[Product.Plus] = it }
        query(TOPUP_ID, ProductType.INAPP)?.let { found[Product.TopUp] = it }
        details = found
        _prices.value = found.mapNotNull { (k, pd) -> price(pd)?.let { k to it } }.toMap()
    }

    private suspend fun query(id: String, type: String): ProductDetails? = suspendCancellableCoroutine { cont ->
        val params = QueryProductDetailsParams.newBuilder().setProductList(
            listOf(QueryProductDetailsParams.Product.newBuilder().setProductId(id).setProductType(type).build()),
        ).build()
        client.queryProductDetailsAsync(params) { r, result ->
            val pd = if (r.responseCode == BillingResponseCode.OK) result.productDetailsList.firstOrNull() else null
            if (cont.isActive) cont.resume(pd)
        }
    }

    @Suppress("DEPRECATION")
    private fun price(pd: ProductDetails): String? =
        pd.subscriptionOfferDetails?.firstOrNull()?.pricingPhases?.pricingPhaseList?.lastOrNull()?.formattedPrice
            ?: pd.oneTimePurchaseOfferDetails?.formattedPrice

    private suspend fun purchases(type: String): List<Purchase> = suspendCancellableCoroutine { cont ->
        client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(type).build()) { r, list ->
            if (cont.isActive) cont.resume(if (r.responseCode == BillingResponseCode.OK) list else emptyList())
        }
    }

    companion object {
        // Must match the product IDs set up in Play Console (and PLAY_PLUS_ID / PLAY_TOPUP_ID on the server).
        const val PLUS_ID = "plus_monthly"
        const val TOPUP_ID = "topup_500"
    }
}
