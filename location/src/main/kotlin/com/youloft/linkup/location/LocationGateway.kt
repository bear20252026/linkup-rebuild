package com.youloft.linkup.location

/**
 * The backend half of the seam: "this fix happened, file it", with no opinion about which server, which
 * envelope, or which bean field order does it.
 *
 * Only the write side is abstracted here, deliberately. The read side (`api/location/days`,
 * `api/location/v2/tracks`) is already pinned to the recovered response beans in `:net` and verified
 * end-to-end against the rebuilt Go gateway — re-modelling those shapes in a vendor-neutral type would
 * create a second source of truth for the same wire format, which is the one thing a contract this
 * signature-sensitive should not get.
 *
 * ## Implementing this
 *
 * See `LinkUpLocationGateway` in `:app`, which is the only production implementation and which owns the
 * mapping from [LocationFix] to the recovered ten-key `Location` bean. A new backend (a different gateway,
 * a test double, another product's API) implements this and nothing else has to change.
 */
interface LocationGateway {
    /**
     * Sends one fix. Suspending, no return value on success: the recovered client ignored the response
     * body entirely (`gh/a.java:401` only checks `status`), so a gateway that can give nothing back is a
     * valid implementation.
     *
     * @param kind why this fix is being sent, which the recovered app encoded in the bean's `type` field.
     * @throws anything on transport failure; [LocationCollector] catches it and keeps the fix for retry.
     */
    suspend fun report(fix: LocationFix, kind: ReportKind)
}

/**
 * The recovered app's `Location.type` — the reason a fix is being reported, which the server reads back as
 * an arrival or departure row (`server/internal/location` treats it as the arrival/departure marker).
 *
 * These are the numbers observed at the call sites, not a guess at a scheme:
 * 0 is what a fresh fix carries before classification (`manager/x0.java:304`), 2 is stamped when a
 * potential stay point matures into a stay (`x0.java:251`, logged `[自动报备]:停留点`), 3 when the device
 * is judged to have left (`x0.java:203`, `x0.java:211`, logged `[自动报备]:离开点`), and 4 when a fix is
 * forwarded to the widgets while otherwise unclassified (`x0.java:1418`).
 */
enum class ReportKind(val wireType: Int) {
    UNCLASSIFIED(0),
    STAY(2),
    LEAVE(3),
    UNCHANGED(4),
}
