# Echo - R8 full mode rules.
# The app has no reflection, JNI, or custom serialization: org.json and the
# Compose runtime ship their own consumer rules, so shrinking is safe by default.

# AGP keeps manifest-declared components (MainActivity) automatically; keep the
# line below explicit so a future refactor cannot silently strip the launcher.
-keep class app.echo.MainActivity { *; }

# Compose emits @Composable lambdas as static methods called directly - nothing
# to keep. Keep BuildConfig only if a future build gates features on it.
-keep class app.echo.BuildConfig { public static final java.lang.String API_ORIGIN; }

# Silence optional annotations referenced by Kotlin stdlib/Compose.
-dontwarn org.jetbrains.annotations.**
