package dev.fileagent.app

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** GitHub version: Stripe checkout pages, opened in the browser. */
fun makeStore(app: Application, hosted: HostedAccount): Store = StripeStore(app)

private class StripeStore(private val app: Application) : Store {
    override val prices: StateFlow<Map<Product, String>> =
        MutableStateFlow(mapOf(Product.Plus to "£4.99", Product.TopUp to "£2.99"))

    override fun ready(product: Product, hosted: HostedState) = link(product, hosted).isNotBlank()

    override suspend fun buy(activity: Activity, product: Product, hosted: HostedState): String? {
        val link = link(product, hosted)
        if (link.isBlank()) return "Payments open soon."
        return runCatching {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
            null
        }.getOrElse { "Can't open the payment page." }
    }

    // Stripe tells our server directly; the panel reloads the balance when you come back.
    override fun sync(onChange: (String?) -> Unit) = Unit

    private fun link(p: Product, h: HostedState) = if (p == Product.Plus) h.planLink else h.packLink
}
