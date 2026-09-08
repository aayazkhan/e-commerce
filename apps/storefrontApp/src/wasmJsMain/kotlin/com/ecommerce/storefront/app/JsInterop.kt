package com.ecommerce.storefront.app

/** The query string of the current page (e.g. "?checkoutId=chk_1&payment=success"), read once at
 * startup -- this is how payment-service's PayU callback hands control back to the app after the
 * browser round-trips through PayU's hosted checkout page (see PaymentProviderImpl.kt's
 * PayuPaymentProvider on the backend). */
@JsFun("() => window.location.search")
external fun currentLocationSearch(): String

/** Drops the query string from the visible URL without reloading the page, so a refresh doesn't
 * replay the same payment-result params. */
@JsFun("() => { window.history.replaceState(null, '', window.location.pathname); }")
external fun clearLocationSearch()

/**
 * Builds a real HTML <form> from [fieldsJson] (a flat JSON object of field name -> string value)
 * and submits it as a top-level POST to [actionUrl]. PayU's hosted checkout requires an actual
 * browser navigation (not fetch/XHR) carrying these exact fields, which is what a submitted form
 * does; nothing about this can go through ApiClient.
 */
@JsFun("""
(actionUrl, fieldsJson) => {
    const fields = JSON.parse(fieldsJson);
    const form = document.createElement('form');
    form.method = 'POST';
    form.action = actionUrl;
    for (const name in fields) {
        const input = document.createElement('input');
        input.type = 'hidden';
        input.name = name;
        input.value = fields[name];
        form.appendChild(input);
    }
    document.body.appendChild(form);
    form.submit();
}
""")
external fun submitRedirectForm(actionUrl: String, fieldsJson: String)

/** localStorage survives the full-page top-level navigation a PayU redirect requires, unlike
 * this app's in-memory Compose state (session token, cart, pending-checkout bookkeeping) -- see
 * main.kt's handling of the payment-result query params PayU's callback redirects back with. */
@JsFun("(key) => window.localStorage.getItem(key) || ''")
external fun localStorageGet(key: String): String

@JsFun("(key, value) => { window.localStorage.setItem(key, value); }")
external fun localStorageSet(key: String, value: String)

@JsFun("(key) => { window.localStorage.removeItem(key); }")
external fun localStorageRemove(key: String)
