package com.teicrypto.syspulse

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Google Play Billing: gói Premium 3 tháng (đăng ký tự gia hạn).
 * Tạo trong Play Console: Monetize → Subscriptions → Product ID = PRODUCT_ID, base plan chu kỳ 3 tháng, giá 9.999 ₫.
 * Trạng thái Premium lấy từ Google Play (queryPurchases), có lưu bản nhớ tạm tối đa 7 ngày khi mất mạng.
 */
object PlayBilling {
    const val PRODUCT_ID = "premium_3m"
    private const val KEY_OK = "play_premium"
    private const val KEY_AT = "play_premium_checked"
    private const val OFFLINE_MS = 7L * 24 * 3600 * 1000

    private lateinit var app: Context
    private var client: BillingClient? = null
    @Volatile private var ready = false
    @Volatile private var details: ProductDetails? = null
    @Volatile private var active = false
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<Runnable>()

    fun addListener(r: Runnable) { listeners.add(r) }
    fun removeListener(r: Runnable) { listeners.remove(r) }
    private fun changed() { main.post { listeners.forEach { it.run() } } }

    /** Premium từ Google Play (kể cả bản nhớ tạm), chưa tính gói do máy chủ cấp. */
    fun isPremium(c: Context): Boolean {
        if (active) return true
        val sp = Prefs.sp(c)
        return sp.getBoolean(KEY_OK, false) && System.currentTimeMillis() - sp.getLong(KEY_AT, 0L) < OFFLINE_MS
    }

    /** Premium từ bất kỳ nguồn nào: Google Play hoặc gói được cấp trong Firestore. */
    fun hasPremium(c: Context): Boolean = isPremium(c) || (Account.cached?.isPremium == true)

    /** Giá hiển thị đã định dạng theo nước của người dùng (vd "9.999 ₫"), null nếu chưa tải được. */
    fun priceText(): String? =
        details?.subscriptionOfferDetails?.firstOrNull()?.pricingPhases?.pricingPhaseList?.firstOrNull()?.formattedPrice

    fun init(c: Context) {
        app = c.applicationContext
        if (client == null) {
            client = BillingClient.newBuilder(app)
                .setListener { result, list -> onUpdated(result, list) }
                .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
                .build()
        }
        connect()
    }

    private fun connect() {
        val cl = client ?: return
        if (ready || cl.connectionState == BillingClient.ConnectionState.CONNECTING) return
        cl.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(r: BillingResult) {
                if (r.responseCode == BillingClient.BillingResponseCode.OK) {
                    ready = true
                    queryDetails()
                    refresh()
                }
            }
            override fun onBillingServiceDisconnected() { ready = false }
        })
    }

    private fun queryDetails() {
        val cl = client ?: return
        val p = QueryProductDetailsParams.newBuilder().setProductList(
            listOf(
                QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(PRODUCT_ID)
                    .setProductType(BillingClient.ProductType.SUBS)
                    .build()
            )
        ).build()
        cl.queryProductDetailsAsync(p) { r, res ->
            if (r.responseCode == BillingClient.BillingResponseCode.OK) {
                details = res.productDetailsList.firstOrNull()
                changed()
            }
        }
    }

    /** Hỏi lại Google Play xem người dùng còn đăng ký hay không. */
    fun refresh() {
        val cl = client
        if (cl == null || !ready) { if (client != null) connect(); return }
        cl.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build()
        ) { r, list ->
            if (r.responseCode == BillingClient.BillingResponseCode.OK) handle(list, true)
        }
    }

    private fun handle(list: List<Purchase>?, authoritative: Boolean) {
        var ok = false
        list?.forEach { p ->
            if (p.purchaseState == Purchase.PurchaseState.PURCHASED && p.products.contains(PRODUCT_ID)) {
                ok = true
                if (!p.isAcknowledged) {
                    client?.acknowledgePurchase(
                        AcknowledgePurchaseParams.newBuilder().setPurchaseToken(p.purchaseToken).build()
                    ) { }
                }
            }
        }
        if (ok || authoritative) {
            active = ok
            Prefs.sp(app).edit().putBoolean(KEY_OK, ok).putLong(KEY_AT, System.currentTimeMillis()).apply()
            changed()
        }
    }

    private fun onUpdated(r: BillingResult, list: List<Purchase>?) {
        when (r.responseCode) {
            BillingClient.BillingResponseCode.OK -> handle(list, false)
            BillingClient.BillingResponseCode.USER_CANCELED -> {}
            else -> main.post { Toast.makeText(app, R.string.premium_error, Toast.LENGTH_LONG).show() }
        }
    }

    fun launch(a: Activity) {
        val cl = client
        val d = details
        val offer = d?.subscriptionOfferDetails?.firstOrNull()
        if (cl == null || d == null || offer == null) {
            Toast.makeText(a, R.string.premium_not_ready, Toast.LENGTH_LONG).show()
            init(a)
            return
        }
        val pd = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(d).setOfferToken(offer.offerToken).build()
        val b = BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(pd))
        Account.user?.uid?.let { b.setObfuscatedAccountId(it) }
        cl.launchBillingFlow(a, b.build())
    }

    /** Mở trang quản lý/hủy đăng ký trên Google Play. */
    fun manage(a: Activity) {
        a.startActivity(
            Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://play.google.com/store/account/subscriptions?sku=$PRODUCT_ID&package=${a.packageName}")
            )
        )
    }
}
