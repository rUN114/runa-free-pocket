# Device tests are a separate APK. R8 cannot see their Kotlin calls while shrinking
# the application, so retain only the runtime entry points those tests actually use.
# Keep release optimization enabled; do not retain the entire Kotlin standard library.
-keep class kotlin.text.StringsKt** {
    public static *** startsWith$default(...);
    public static *** endsWith$default(...);
    public static *** contains$default(...);
}
-keep class kotlin.collections.CollectionsKt** {
    public static java.util.List listOf(java.lang.Object[]);
}
-keep class kotlin.collections.SetsKt** {
    public static java.util.Set setOf(java.lang.Object);
}
-keep class kotlin.io.CloseableKt {
    public static void closeFinally(java.io.Closeable, java.lang.Throwable);
}
-keep class kotlin.jvm.internal.Intrinsics {
    public static boolean areEqual(java.lang.Object, java.lang.Object);
    public static void checkNotNull(java.lang.Object);
    public static void checkNotNull(java.lang.Object, java.lang.String);
    public static void checkNotNullExpressionValue(java.lang.Object, java.lang.String);
    public static void checkNotNullParameter(java.lang.Object, java.lang.String);
    public static void throwUninitializedPropertyAccessException(java.lang.String);
}
-keep class kotlin.jvm.internal.Ref$BooleanRef {
    public <init>();
    public boolean element;
}
-keep class kotlin.jvm.internal.Ref$IntRef {
    public <init>();
    public int element;
}
-keep class kotlin.jvm.internal.Ref$ObjectRef {
    public <init>();
    public java.lang.Object element;
}
-keep class kotlin.text.Regex {
    public <init>(java.lang.String);
    public java.lang.String replaceFirst(java.lang.CharSequence, java.lang.String);
}
