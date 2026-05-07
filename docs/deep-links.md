# Deep Links

NWC setup commonly starts in one of two ways:

- The user scans or pastes a direct `nostr+walletconnect` URI.
- Your app asks a wallet app for a connection with `nostrnwc://connect`, then
  receives the connection string in a callback.

The SDK supports both.

## Direct Connection Links

Direct NWC links can be parsed as connection URIs:

```kotlin
val uri = NwcConnectionUri.parse("nostr+walletconnect://...")
    ?: error("Invalid NWC URI")

val client = NwcClient(uri, scope)
```

They can also be parsed through the deep-link helper:

```kotlin
when (val link = NwcDeepLink.parse(input)) {
    is NwcDeepLink.ConnectionUri -> {
        val client = NwcClient(link.uri, scope)
    }
    is NwcDeepLink.Callback -> {
        println("Callback value: ${link.value}")
    }
    null -> {
        println("Not an NWC link")
    }
}
```

If a callback contains a valid connection URI in its `value` parameter,
`NwcDeepLink.parse` returns `ConnectionUri`, not `Callback`.

## Build a Wallet Connect Request

Use `NwcConnectRequest` to ask a wallet app to create an NWC connection.

If you want to use the SDK's `NwcDeepLink.parse` or `NwcClient.fromDeepLink`
helpers for the callback, use a `nostrnwc://...` callback URI:

```kotlin
val connectRequestUri = NwcConnectRequest.Builder()
    .appName("Example App")
    .appIcon("https://example.com/icon.png")
    .callback("nostrnwc://connect")
    .build()
    .toUri()
```

Open `connectRequestUri` with your platform's URL launcher. The wallet should
return the connection URI to the callback you provided.

You can also use a custom callback scheme:

```kotlin
val connectRequestUri = NwcConnectRequest.Builder()
    .appName("Example App")
    .callback("example://nwc-callback")
    .build()
    .toUri()
```

For custom callback schemes, use your platform URL parser to extract the
decoded `value` query parameter, then pass it to `NwcConnectionUri.parse` or
`NwcClient.fromUri`.

Target a specific wallet app by scheme prefix:

```kotlin
val connectRequestUri = NwcConnectRequest.Builder()
    .walletApp("walletname")
    .appName("Example App")
    .callback("example://nwc-callback")
    .build()
    .toUri()
```

This produces a `nostrnwc+walletname://connect?...` URI.

## Parse a Callback

Use the high-level factory when all you need is a client and the link is either
a direct `nostr+walletconnect` URI or a `nostrnwc://...` callback:

```kotlin
val client = NwcClient.fromDeepLink(callbackUri, scope)
    ?: error("Invalid NWC callback")
```

Use `NwcDeepLink.parse` when your UI needs to distinguish direct links from
callbacks:

```kotlin
fun handleNwcLink(uri: String): NwcConnectionUri? {
    return when (val link = NwcDeepLink.parse(uri)) {
        is NwcDeepLink.ConnectionUri -> link.uri
        is NwcDeepLink.Callback -> NwcConnectionUri.parse(link.value)
        null -> null
    }
}
```

The callback shape expected by the parser is:

```text
nostrnwc://connect?value=<url-encoded-nwc-uri>
```

For a custom callback such as `example://nwc-callback?value=...`, parse the
query parameter with platform APIs. On Android, `Uri.getQueryParameter("value")`
returns a decoded value:

```kotlin
val connectionUri = intent?.data?.getQueryParameter("value")
val client = connectionUri?.let { NwcClient.fromUri(it, viewModelScope) }
```

## Android Registration

Register direct NWC links if your app accepts raw connection URIs:

```xml
<intent-filter>
    <action android:name="android.intent.action.VIEW" />
    <category android:name="android.intent.category.DEFAULT" />
    <category android:name="android.intent.category.BROWSABLE" />
    <data android:scheme="nostr+walletconnect" />
</intent-filter>
```

Register `nostrnwc` if you use the SDK-parsed callback shape:

```xml
<intent-filter>
    <action android:name="android.intent.action.VIEW" />
    <category android:name="android.intent.category.DEFAULT" />
    <category android:name="android.intent.category.BROWSABLE" />
    <data android:scheme="nostrnwc" />
</intent-filter>
```

Or register your custom callback scheme if you use one:

```xml
<intent-filter>
    <action android:name="android.intent.action.VIEW" />
    <category android:name="android.intent.category.DEFAULT" />
    <category android:name="android.intent.category.BROWSABLE" />
    <data android:scheme="example" android:host="nwc-callback" />
</intent-filter>
```

Handle a direct URI or `nostrnwc://...` callback and pass it to the SDK:

```kotlin
val deepLink = intent?.dataString
val client = deepLink?.let { NwcClient.fromDeepLink(it, viewModelScope) }
```

For a custom callback, extract `value` first:

```kotlin
val connectionUri = intent?.data?.getQueryParameter("value")
val client = connectionUri?.let { NwcClient.fromUri(it, viewModelScope) }
```

## iOS Registration

Register the schemes your app receives in `Info.plist`:

```xml
<key>CFBundleURLTypes</key>
<array>
    <dict>
        <key>CFBundleURLSchemes</key>
        <array>
            <string>nostr+walletconnect</string>
            <string>nostrnwc</string>
            <string>example</string>
        </array>
    </dict>
</array>
```

Forward the opened URL string into shared code:

```kotlin
val client = NwcClient.fromDeepLink(openedUrlString, scope)
```

For custom callback schemes, extract the decoded `value` query parameter with
`URLComponents` and pass that value to `NwcClient.fromUri`.

## Security Notes

The callback value contains the NWC secret. Treat it as sensitive input:

- Avoid logging full deep-link URLs.
- Store accepted connection strings in secure storage.
- Clear rejected connection strings from temporary UI state.
