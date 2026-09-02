package com.ecommerce.platform.security

import com.ecommerce.platform.common.RequestMetadata

enum class Role {
    CUSTOMER,
    SELLER,
    ADMIN,
    SUPER_ADMIN,
    SUPPORT,
    WAREHOUSE,
    FINANCE,
    CONTENT_MANAGER,
}

enum class Permission {
    PRODUCT_CREATE,
    PRODUCT_UPDATE,
    PRODUCT_DELETE,
    PRODUCT_PUBLISH,
    CATEGORY_CREATE,
    CATEGORY_UPDATE,
    CATEGORY_DELETE,
    PRICE_CREATE,
    PRICE_UPDATE,
    PRICE_DELETE,
    MEDIA_UPLOAD,
    MEDIA_DELETE,
    SEARCH_REINDEX,
    CART_READ,
    CART_WRITE,
    WISHLIST_READ,
    WISHLIST_WRITE,
    PROMOTION_READ,
    PROMOTION_APPLY,
    PROMOTION_CREATE,
    PROMOTION_UPDATE,
    PROMOTION_DELETE,
    COUPON_CREATE,
    COUPON_UPDATE,
    COUPON_DELETE,
    INVENTORY_READ,
    INVENTORY_ADJUST,
    INVENTORY_RESERVE,
    INVENTORY_COMMIT,
    ORDER_READ,
    ORDER_CREATE,
    ORDER_CANCEL,
    ORDER_RETURN,
    PAYMENT_READ,
    PAYMENT_CREATE,
    REFUND_CREATE,
    REFUND_READ,
    REFUND_APPROVE,
    SHIPMENT_READ,
    SHIPMENT_CREATE,
    SHIPMENT_UPDATE,
    ADMIN_ORDER_READ,
    ADMIN_ORDER_UPDATE,
    ADMIN_PAYMENT_READ,
    ADMIN_REFUND_APPROVE,
    ADMIN_SHIPPING_UPDATE,
    ORDER_VIEW,
    ORDER_UPDATE,
    USER_MANAGE,
    PAYMENT_VIEW,
    REPORT_VIEW,
}

data class AuthenticatedPrincipal(
    val actorId: String,
    val roles: Set<Role>,
    val permissions: Set<Permission>,
    val tenantId: String?,
)

fun AuthenticatedPrincipal.toRequestMetadata(requestId: String, traceId: String?): RequestMetadata =
    RequestMetadata(
        requestId = requestId,
        traceId = traceId,
        actorId = actorId,
        tenantId = tenantId,
    )

fun AuthenticatedPrincipal.require(permission: Permission) {
    require(permission in permissions || Role.SUPER_ADMIN in roles) {
        "Missing permission: $permission"
    }
}
